/*
 * Android/OpenCL adaptation of Garlic LLM's Qwen3.5 inference engine.
 * Derived from upstream chat/engine.c; GPL-3.0-or-later.
 * Original: https://github.com/leonardo-alegria/garlic-llm
 * Android changes: replace PS4 GNM/PM4 and direct-memory calls with clvk OpenCL
 * buffers, kernels, queue dispatch and explicit host/device copies.
 */
// Qwen3 / Qwen3.5 inference on the PS4 GPU from a native app (the same command packets as pc/gpu.py).
//
// One block of Garlic memory (CPU write-combined, GPU read/write) holds everything the GPU touches. Each run builds
// a command buffer: Sony's default state, then for every token ~400 compute dispatches (embed, per layer: norm,
// q/k/v, q/k norm + RoPE + cache, attention, o, add, norm, gate/up, SiLU x up, down, add; final norm, logits,
// sample), each followed by CS_PARTIAL_FLUSH and an ACQUIRE_MEM that drops stale L1 / scalar cache lines; then an
// end-of-pipe event writes a label the CPU polls. Kernels come from assets/kernels.bin (shaders/qwen3.cl or,
// for Qwen3.5, shaders/qwen35.cl, whose DeltaNet layers replace attention by conv + delta rule).

#include "engine.h"

#include <fcntl.h>
#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>
#include <time.h>
#include <sys/types.h>
#include <unistd.h>
#include <stdint.h>
#include <errno.h>
#include <string.h>
#include "garlic_opencl_runtime.h"

#define SH_START_X 0x204
#define SH_NUM_THREAD_X 0x207
#define SH_PGM_LO 0x20C
#define SH_PGM_RSRC1 0x212
#define SH_RESOURCE_LIMITS 0x215
#define SH_STATIC_THREAD_MGMT_SE0 0x216
#define SH_TMPRING_SIZE 0x218
#define SH_USER_DATA_0 0x240
#define EV_CS_PARTIAL_FLUSH (7 | (4 << 8))
#define EV_BOTTOM_OF_PIPE_TS (0x28 | (5 << 8))
#define EV_CACHE_FLUSH_AND_INV_TS (0x14 | (5 << 8))
#define COHER_TCL1_KCACHE ((1u << 22) | (1u << 27))
// The GPU's L2 is write-back and does not see the CPU's writes: invalidate it (writing back dirty lines) when a
// submission starts, so kernel arguments, staged rows and the like that the CPU just rewrote at the same addresses are
// fetched fresh, and write it back before the end-of-pipe label, so the CPU reads the GPU's last results.
// (CP_COHER_CNTL TC_ACTION_ENA; on the end-of-pipe event, Gnm's kCacheActionWriteBackAndInvalidateL1andL2 = 0x38 << 12.)
#define COHER_L2_TCL1_KCACHE ((1u << 22) | (1u << 23) | (1u << 27))
#define EOP_WB_INV_L1_L2 (0x38u << 12)
#define LABEL_DONE 0x600DF00Du
#define NO_TOKEN 0xFFFFFFFFu
#define DCB_BYTES (4u << 20)
#define KARGS_BYTES (4u << 20)
#define STEPS_PER_SUBMIT 40
#define PF_ROWS 32              // prompt tokens per batch (shaders/prefill.h; at most STEPS_PER_SUBMIT staging rows)
#define ALIGN_2MB (2ull << 20)
#define ATT_BLOCK 64            // ATT_BLOCK in shaders/attn.h (MAX_SEQ comes from model.idx)
#define ROW_CACHE 4096          // embedding rows kept in memory (power of two)
#define SWAR_SPLIT_ROWS 12416   // split-K (partial sums) only for matrices up to this many rows

void say(const char* fmt, ...) {
    char message[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(message, sizeof(message), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, "MATRIX-GARLIC", "%s", message);
}
static void mm_tune(Engine* e);   // (after engine_run)
static void swar_tune(Engine* e);
static uint64_t pf_a_floats, pf_h_floats;   // sizes of the batched prompt's buffers (swar_tune uses them)
#ifdef GARLIC_SPEC
// speculative decoding with the MTP head (engine_spec.inc, at the end)
int spec_active(const Engine* e);
static void spec_reset(Engine* e);
static void spec_prefill(Engine* e, uint64_t stage, uint32_t n, uint32_t pos0);
uint64_t spec_carry(void);
#endif
static int gpu_selftest(Engine* e);   // (after begin)

static void fail(Engine* e, const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(e->error, sizeof(e->error), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_ERROR, "MATRIX-GARLIC", "%s", e->error);
}

// Where a weights file is: inside the package (/app0/models, the all-in-one PKG) or, with an app-only package, at the
// path its index names (in /data). The first one that exists; path (129 bytes or more) is rewritten in place.
static void weights_locate(char* path, const char* dir) {
    const char* base = strrchr(path, '/');
    const char* name = base ? base + 1 : path;
    char resolved[512];
    snprintf(resolved, sizeof(resolved), "%s/%s", dir, name);
    snprintf(path, 512, "%s", resolved);
}

static uint8_t* read_file(const char* path, size_t* size) {
    int f = open(path, O_RDONLY);
    if (f < 0) return NULL;
    off_t n = lseek(f, 0, SEEK_END);
    lseek(f, 0, SEEK_SET);
    uint8_t* buf = malloc(n);
    size_t done = 0;
    while (buf && done < (size_t)n) {
        ssize_t r = read(f, buf + done, n - done);
        if (r <= 0) break;
        done += r;
    }
    close(f);
    if (!buf || done != (size_t)n) {
        free(buf);
        return NULL;
    }
    *size = n;
    return buf;
}

static uint64_t cpu_alloc(Engine* e, uint64_t size) {
    uint64_t off = (e->next + 255) & ~255ull;
    if (!size || off > e->mem_size || size > e->mem_size - off) return 0;
    e->next = off + size;
    return (uint64_t)(uintptr_t)(e->mem + off);
}

/* Every GPU allocation gets its own OpenCL buffer. This mirrors Garlic's address-based
 * allocator without asking Android's Vulkan driver for one multi-gigabyte cl_mem object. */
static uint64_t gpu_alloc(Engine* e, uint64_t size) {
    uint64_t address = cpu_alloc(e, size);
    if (!address) return 0;
    MatrixClRuntime* runtime = (MatrixClRuntime*)e->android_cl_runtime;
    if (!runtime || size > matrix_cl_max_allocation(runtime)) {
        fail(e, "Garlic GPU allocation of %llu bytes exceeds the Vulkan device allocation limit.",
             (unsigned long long)size);
        return 0;
    }
    char error[256] = {0};
    if (matrix_cl_register_region(runtime, (uintptr_t)address, (size_t)size,
                                  (const void*)(uintptr_t)address, error, sizeof(error)) != 0) {
        fail(e, "%s", error[0] ? error : "Could not register Garlic GPU memory region.");
        return 0;
    }
    return address;
}

int eng_margin_mb = 64;

static int reserve_garlic(Engine* e) {
    uint64_t full_layers = 0;
    for (uint32_t i = 0; i < e->n_layers; ++i) full_layers += (e->full_mask >> i) & 1u;

    e->conv_n = 2ull * e->n_kh * e->dk + (uint64_t)e->n_vh * e->dv;
    e->state_bytes = (uint64_t)e->n_vh * e->dk * e->dv * 4ull;

    // Exact upper bound for the current Qwen3.5 cache layout, plus inference scratch.
    const uint64_t kv_bytes = full_layers * (uint64_t)e->max_seq * e->n_kv * (3ull * e->head_dim + 4ull);
    const uint64_t delta_bytes = (e->n_layers - full_layers) *
        (e->conv_n * 12ull + e->state_bytes);
    const uint64_t rope_bytes = (uint64_t)e->max_seq * e->rope_dim * sizeof(float);
    const uint64_t scratch_bytes = 160ull << 20;
    uint64_t required = e->weights_size + kv_bytes + delta_bytes + rope_bytes + scratch_bytes;
    required = (required + ((32ull << 20) - 1)) & ~((32ull << 20) - 1);

    const long pages = sysconf(_SC_AVPHYS_PAGES);
    const long page_size = sysconf(_SC_PAGESIZE);
    if (pages <= 0 || page_size <= 0) {
        fail(e, "Android could not determine available system memory.");
        return -1;
    }
    const uint64_t available = (uint64_t)pages * (uint64_t)page_size;
    // The CPU pool and the Vulkan/OpenCL mirror coexist. Keep a generous reserve for Android,
    // the VPN tunnel and the graphics driver rather than forcing the phone into memory pressure.
    const uint64_t reserve = 192ull << 20;
    const uint64_t usable = available > reserve ? available - reserve : 0;
    if (required > available || required > usable / 2ull) {
        fail(e, "Model needs approximately %llu MiB for CPU+GPU memory; Android currently has %llu MiB available.",
             (unsigned long long)(required * 2ull >> 20),
             (unsigned long long)(available >> 20));
        return -1;
    }
    void* pool = NULL;
    if (posix_memalign(&pool, 4096, (size_t)required) != 0 || !pool) {
        fail(e, "Android could not allocate %llu MiB for the Garlic model pool.",
             (unsigned long long)(required >> 20));
        return -1;
    }
    memset(pool, 0, (size_t)required);
    e->mem = (uint8_t*)pool;
    e->mem_size = required;
    e->next = 0;
    return 0;
}

static const GpuKernel* kernel(Engine* e, const char* name) {
    for (int i = 0; i < e->nk; ++i) {
        if (!strcmp(e->k[i].name, name)) return &e->k[i];
    }
    if (!e->android_cl_runtime || e->nk >= (int)(sizeof(e->k) / sizeof(e->k[0]))) {
        fail(e, "Garlic kernel table is not ready for %s.", name);
        return NULL;
    }
    GpuKernel* item = &e->k[e->nk];
    memset(item, 0, sizeof(*item));
    snprintf(item->name, sizeof(item->name), "%s", name);
    char error[256] = {0};
    item->android_handle = matrix_cl_get_kernel((MatrixClRuntime*)e->android_cl_runtime, name,
                                                 error, sizeof(error));
    if (!item->android_handle) {
        fail(e, "%s", error[0] ? error : "Could not create Garlic OpenCL kernel.");
        return NULL;
    }
    ++e->nk;
    return item;
}

static int load_kernels(Engine* e, const char* dir) {
    char path[512];
    size_t size = 0;
    snprintf(path, sizeof(path), "%s/qwen35.spv", dir);
    uint8_t* il = read_file(path, &size);
    if (!il) {
        fail(e, "Cannot read adapted Garlic Qwen3.5 SPIR-V module: %s", path);
        return -1;
    }
    char error[512] = {0};
    e->android_cl_runtime = matrix_cl_create(il, size, error, sizeof(error));
    free(il);
    if (!e->android_cl_runtime) {
        fail(e, "%s", error[0] ? error : "Cannot initialise Android Vulkan/OpenCL runtime.");
        return -1;
    }
    if (matrix_cl_max_workgroup((MatrixClRuntime*)e->android_cl_runtime) < 256) {
        fail(e, "This Vulkan GPU supports workgroups smaller than 256; the current Garlic Qwen kernels cannot run.");
        return -1;
    }

    // The mobile port starts with the standard Q4/Q4i decode path. TurboQuant, SWAR/Q4S/Q3S,
    // speculative decoding and batched prefill remain disabled until their Android kernels
    // have passed numerical reference tests.
    e->tq = 0;
    e->tq16 = 0;
    if (e->swar) {
        fail(e, "This Android build does not yet support Garlic's Q4S/Q3S SWAR weight layout.");
        return -1;
    }
    e->pf = 0;
    e->pf_off = 1;

    const char* embedName = (e->t[0].fmt == 3 || e->t[0].fmt == 4) ? "embed_q4i" : "embed_q4";
    e->k_embed = kernel(e, embedName);
    e->k_bsum = kernel(e, "block_sums");
    e->k_mvi = kernel(e, "matvec_q4i");
    e->k_mvi8 = kernel(e, "matvec_q4i8");
    e->k_mv3 = kernel(e, "matvec_q3i");
    e->k_mv38 = kernel(e, "matvec_q3i8");
    e->k_copy = kernel(e, "copy_f32");
    e->k_rmsnorm = kernel(e, "rmsnorm");
    e->k_qk = kernel(e, "qk_rope_cache");
    e->k_attn = kernel(e, "attn_part");
    e->k_attn_comb = kernel(e, "attn_combine");
    e->k_silu = kernel(e, "silu_mul");
    e->k_add = kernel(e, "add");
    e->k_mv = kernel(e, "matvec_q4");
    e->k_sample = kernel(e, "sample");
    e->k_sig = kernel(e, "mul_sigmoid");
    e->k_conv = kernel(e, "conv_silu");
    e->k_delta = kernel(e, "delta_step");
    if (!e->k_embed || !e->k_bsum || !e->k_mvi || !e->k_mvi8 || !e->k_mv3 || !e->k_mv38 || !e->k_copy || !e->k_rmsnorm ||
        !e->k_qk || !e->k_attn || !e->k_attn_comb || !e->k_silu || !e->k_add ||
        !e->k_mv || !e->k_sample || !e->k_sig || !e->k_conv || !e->k_delta) {
        fail(e, "The adapted Garlic Qwen3.5 shader module is missing one or more required kernels.");
        return -1;
    }
    return 0;
}

static int load_index(Engine* e, const char* dir) {
    char path[256];
    size_t size;
    snprintf(path, sizeof(path), "%s/model.idx", dir);
    uint8_t* d = read_file(path, &size);
    if (!d || (memcmp(d, "PMDL", 4) && memcmp(d, "PMQ5", 4))) {
        fail(e, "cannot read %s", path);
        return -1;
    }
    e->qwen35 = !memcmp(d, "PMQ5", 4);
    uint32_t v[10];
    memcpy(v, d + 4, 40);
    e->n_layers = v[0], e->hidden = v[1], e->inter = v[2], e->n_heads = v[3], e->n_kv = v[4];
    e->head_dim = v[5], e->vocab = v[6], e->max_seq = v[7], e->eos = v[8], e->eos2 = v[9];
    memcpy(&e->eps, d + 44, 4);
    memcpy(&e->rope_theta, d + 48, 4);
    memcpy(e->weights_path, d + 52, 128);
    e->weights_path[128] = 0;
    weights_locate(e->weights_path, dir);
    memcpy(&e->weights_size, d + 180, 8);
    memcpy(&e->n_tensors, d + 188, 4);
    uint32_t rec0 = 192, vocab_map = 0;
    if (e->qwen35) {   // "<8I" after the header: n_kh, n_vh, dk, dv, rope_dim, full_mask, im_start, 0
        uint32_t x[8];
        memcpy(x, d + 192, 32);
        e->n_kh = x[0], e->n_vh = x[1], e->dk = x[2], e->dv = x[3], e->rope_dim = x[4], e->full_mask = x[5];
        e->im_start = x[6];
        e->has_head = x[7] & 1;
        e->cpu_embed = (x[7] >> 1) & 1;
        vocab_map = (x[7] >> 2) & 1;   // lm_head trimmed, an "ids" record (row -> token id) after it
        rec0 = 224;
    } else {
        e->rope_dim = e->head_dim;
        e->full_mask = 0xFFFFFFFFu;
        e->im_start = 151644;
    }
    e->im_end = e->eos;
    e->layer_base = calloc(e->n_layers, sizeof(uint32_t));
    for (uint32_t i = 0, b = 2; i < e->n_layers; i++) {   // full attention 8 tensors per layer, DeltaNet 10
        e->layer_base[i] = b;
        b += (e->full_mask >> i & 1) ? 8 : 10;
    }
    e->t = calloc(e->n_tensors, sizeof(Tensor));
    for (uint32_t i = 0; i < e->n_tensors; i++) {
        const uint8_t* r = d + rec0 + i * 36;   // "<QQIIQI" records (pc/export_chat.py)
        memcpy(&e->t[i].off, r, 8);
        memcpy(&e->t[i].size, r + 8, 8);
        memcpy(&e->t[i].rows, r + 16, 4);
        memcpy(&e->t[i].cols, r + 20, 4);
        memcpy(&e->t[i].scales, r + 24, 8);
        memcpy(&e->t[i].fmt, r + 32, 4);
        if (e->t[i].fmt == 6 || e->t[i].fmt == 8) e->swar = 1;   // q4s / q3s matrices: SWAR products (spec.h)
    }
    free(d);
    e->head_idx = e->has_head ? e->n_tensors - 1 - vocab_map : 0;
    if (vocab_map && (e->t[e->n_tensors - 1].fmt != 7 || e->t[e->n_tensors - 1].rows != e->vocab)) {
        fail(e, "model.idx: the ids record does not match the vocabulary");
        return -1;
    }
    return 0;
}

// TurboQuant 4-bit KV cache (shaders/attn_tq.h): the 16 Lloyd-Max levels for the coordinates of a rotated head of
// 256, the 15 boundaries between them, 0, then the 8 words of random sign bits; as float bit patterns, from
// pc/turboquant.py (python -c "import turboquant as t; print(t.c_table())")
#define TQ_TABLE \
    0xc02d6e45u, 0xc0039f58u, 0xbfce1f1du, 0xbfa0262eu, 0xbf705f2du, 0xbf2790a4u, 0xbec60879u, 0xbe030d12u, \
    0x3e030d12u, 0x3ec60879u, 0x3f2790a4u, 0x3f705f2du, 0x3fa0262eu, 0x3fce1f1du, 0x40039f58u, 0x402d6e45u, \
    0xc01886ceu, 0xbfeaaee6u, 0xbfb722a6u, 0xbf8c2ae2u, 0xbf4bf7e8u, 0xbf054a70u, 0xbe83c781u, 0x00000000u, \
    0x3e83c781u, 0x3f054a70u, 0x3f4bf7e8u, 0x3f8c2ae2u, 0x3fb722a6u, 0x3feaaee6u, 0x401886ceu, 0x00000000u, \
    0x76452f77u, 0x1d5aee0du, 0xda3d646du, 0x05d4b13du, 0x9642207cu, 0x5d203182u, 0x0d2a2a6du, 0xb4a5b633u

int engine_init(Engine* e, const char* dir, ProgressFn progress, void* user) {
    memset(e, 0, sizeof(*e));
    e->fd = -1;
    e->tsc_freq = 1000000000ull;
    if (load_index(e, dir)) return -1;
    if (reserve_garlic(e)) {
        fail(e, "could not reserve GPU memory");
        return -1;
    }
    char line[200];
    snprintf(line, sizeof(line), "[ps4ai] %u MiB of GPU memory at %p; model %u layers, hidden %u, vocab %u\n",
             (unsigned)(e->mem_size >> 20), e->mem, e->n_layers, e->hidden, e->vocab);
    say("%s", line);

    /* Initialize clvk and cache the Garlic kernels before the first device allocation. */
    if (load_kernels(e, dir)) return -1;

    e->dcb = (uint32_t*)(uintptr_t)cpu_alloc(e, DCB_BYTES);
    e->dcb_cap = DCB_BYTES / 4;
    e->kargs = (uint8_t*)(uintptr_t)cpu_alloc(e, KARGS_BYTES);
    e->kargs_cap = KARGS_BYTES;
    e->label = (volatile uint32_t*)(uintptr_t)cpu_alloc(e, 256);
    e->stamps = (volatile uint64_t*)(uintptr_t)cpu_alloc(e, 256);

    if (gpu_selftest(e)) return -1;

    if (e->tq && (e->head_dim != 256 || e->max_seq != 8192)) e->tq = 0;   // the kernels' shape (qwen35.cl MAX_SEQ)
    // TurboQuant with a kernels.bin whose attn_tq.h takes the split argument: 16K of context, positions 8192.. in
    // kv_hi (while it is not lent to S3Gen)
    if (e->tq && e->tq16) e->max_seq = 2 * 8192;
    // RoPE table: [max_seq][rope_dim] = cos of the first half, then sin (RoPE on the first rope_dim dims of a head)
    uint32_t half = e->rope_dim / 2;
    e->rope = gpu_alloc(e, (uint64_t)e->max_seq * e->rope_dim * 4);
    float* rope = (float*)(uintptr_t)e->rope;
    for (uint32_t p = 0; p < e->max_seq; p++)
        for (uint32_t i = 0; i < half; i++) {
            double ang = p * pow((double)e->rope_theta, -2.0 * i / e->rope_dim);
            rope[(uint64_t)p * e->rope_dim + i] = (float)cos(ang);
            rope[(uint64_t)p * e->rope_dim + half + i] = (float)sin(ang);
        }
    if (matrix_cl_write_address((MatrixClRuntime*)e->android_cl_runtime, (uintptr_t)rope,
                                (size_t)e->max_seq * e->rope_dim * sizeof(float), rope) != 0) {
        fail(e, "Could not upload Garlic RoPE table to Vulkan memory.");
        return -1;
    }

    if (e->head_dim != (e->qwen35 ? 256u : 128u) || e->n_heads / e->n_kv > 4 || e->max_seq % ATT_BLOCK) {
        fail(e, "model shape not supported by the kernels (max_seq %u, head_dim %u)", e->max_seq, e->head_dim);
        return -1;
    }
    // per layer: fp16 KV cache, k transposed [n_kv][head_dim][max_seq], v [max_seq][n_kv][head_dim] (shaders/attn.h);
    // or, for a Gated DeltaNet layer, the conv state [conv_n][3] and the delta-rule state [n_vh][dk][dv] (fp32)
    e->layer_cache = (uint64_t)e->max_seq * e->n_kv * e->head_dim * 2;
    e->conv_n = 2ull * e->n_kh * e->dk + (uint64_t)e->n_vh * e->dv;
    e->state_bytes = (uint64_t)e->n_vh * e->dk * e->dv * 4;
    e->kc = calloc(e->n_layers, sizeof(uint64_t));
    e->vc = calloc(e->n_layers, sizeof(uint64_t));
    e->ctx_limit = e->max_seq;
    e->lend_ctx = e->max_seq / 2;
    if (e->qwen35 && e->tq) {
        // TurboQuant (shaders/attn_tq.h), per full layer and block of 8192 positions: k = 4-bit words
        // [n_kv][head_dim / 8][8192] + scales [n_kv][8192]; v = 4-bit codes [8192][n_kv][head_dim / 2 bytes] +
        // scales [8192][n_kv]. Positions 0..8191 of all the layers in one block (66 MiB). kv_hi is a block of its own,
        // the size of the second halves without TurboQuant (S3Gen's weights in voice mode). With 16K (max_seq 16384)
        // positions 8192.. of each layer are in kv_hi at the same offset (tq_split bytes from the first block):
        // lending it shortens the context to 8192 (lend_ctx); with 8K, lending it leaves the context whole.
        static const uint32_t tab[40] = {TQ_TABLE};
        uint64_t kb = (uint64_t)e->n_kv * 8192 * (e->head_dim / 2 + 4), n_full = 0;
        for (uint32_t i = 0; i < e->n_layers; i++) n_full += e->full_mask >> i & 1;
        uint64_t all = gpu_alloc(e, n_full * 2 * kb);
        e->kv_split = n_full * (4096ull * e->n_kv * e->head_dim * 2 + 4096ull * e->n_kv * (e->head_dim + 4));
        e->kv_hi = e->kv_split ? gpu_alloc(e, e->kv_split) : 0;
        e->lend_ctx = e->max_seq;
        if (e->max_seq > 8192) {
            if (all && e->kv_hi && e->kv_split >= n_full * 2 * kb) {
                e->tq_split = e->kv_hi - all;
                e->lend_ctx = 8192;
            } else {
                e->max_seq = 8192;   // (cannot happen: the block is 96.5 MiB, the second 8K takes 66)
            }
        }
        e->ctx_limit = e->max_seq;
        e->tq_tab = gpu_alloc(e, 256);
        if (e->tq_tab) memcpy((void*)(uintptr_t)e->tq_tab, tab, sizeof(tab));
        for (uint32_t i = 0, j = 0; all && e->tq_tab && i < e->n_layers; i++) {
            if (e->full_mask >> i & 1) {
                e->kc[i] = all + j * 2 * kb;
                e->vc[i] = e->kc[i] + kb;
                j++;
            } else {
                e->kc[i] = gpu_alloc(e, e->conv_n * 12);
                e->vc[i] = gpu_alloc(e, e->state_bytes);
            }
        }
        char line[160];
        snprintf(line, sizeof(line), "[ps4ai] TurboQuant KV cache: %u MiB for 8192 positions (+%u MiB lendable; "
                 "context %u, %u while lent)\n", (unsigned)(n_full * 2 * kb >> 20), (unsigned)(e->kv_split >> 20),
                 e->max_seq, e->lend_ctx);
        say("%s", line);
    } else if (e->qwen35) {
        // values in int8 + a float scale per (position, kv head) (V_INT8 in shaders/qwen35.cl); each full layer's
        // k and v in two halves of max_seq / 2 positions (ATTN_SPLIT): the first halves of all the layers in one
        // block, the second halves in the next, kv_split bytes further
        uint64_t khalf = e->layer_cache / 2, vhalf = (uint64_t)e->max_seq / 2 * e->n_kv * (e->head_dim + 4), n_full = 0;
        for (uint32_t i = 0; i < e->n_layers; i++) n_full += e->full_mask >> i & 1;
        e->kv_split = n_full * (khalf + vhalf);
        uint64_t lo = gpu_alloc(e, e->kv_split);
        e->kv_hi = gpu_alloc(e, e->kv_split);
        if (lo && e->kv_hi && e->kv_hi != lo + e->kv_split) e->kv_hi = 0;   // (gpu_alloc rounds to 256 bytes)
        for (uint32_t i = 0, j = 0; lo && e->kv_hi && i < e->n_layers; i++) {
            if (e->full_mask >> i & 1) {
                e->kc[i] = lo + j * (khalf + vhalf);
                e->vc[i] = e->kc[i] + khalf;
                j++;
            } else {
                e->kc[i] = gpu_alloc(e, e->conv_n * 12);
                e->vc[i] = gpu_alloc(e, e->state_bytes);
            }
        }
    } else {
        for (uint32_t i = 0; i < e->n_layers; i++) {
            int full = e->full_mask >> i & 1;
            e->kc[i] = gpu_alloc(e, full ? e->layer_cache : e->conv_n * 12);
            e->vc[i] = gpu_alloc(e, full ? e->layer_cache : e->state_bytes);
        }
    }
    for (uint32_t i = 0; i < e->n_layers; i++)
        if (!e->kc[i] || !e->vc[i]) {
            fail(e, "caches do not fit in %u MiB", (unsigned)(e->mem_size >> 20));
            return -1;
        }
    e->x = gpu_alloc(e, e->hidden * 4);
    e->xn = gpu_alloc(e, e->hidden * 4);
    e->attn = gpu_alloc(e, (uint64_t)(e->n_heads * e->head_dim > e->n_vh * e->dv ? e->n_heads * e->head_dim
                                                                                  : e->n_vh * e->dv) * 4);
    // (the TurboQuant kernels of a kernels.bin with the batched prompt always lay it out for 16K, 256 blocks)
    uint32_t part_seq = e->max_seq;
    if (e->tq16 && part_seq < 16384) part_seq = 16384;
    e->attn_part = gpu_alloc(e, (uint64_t)e->n_heads * (part_seq / ATT_BLOCK) * (e->head_dim + 2) * 4);
    e->hmlp = gpu_alloc(e, e->inter * 4);
    e->bsum = gpu_alloc(e, (uint64_t)(e->inter > e->hidden ? e->inter : e->hidden) / 32 * 4 + 4096);
    uint32_t stage_rows = STEPS_PER_SUBMIT;
    if (e->pf && e->cpu_embed)
        stage_rows = 2 * PF_ROWS > STEPS_PER_SUBMIT ? 2 * PF_ROWS : STEPS_PER_SUBMIT;   // two batches of the prompt
    e->staging = gpu_alloc(e, (uint64_t)stage_rows * e->hidden * 4);
    // q | k | v (| output gate, Qwen3.5); the DeltaNet input projection: q | k | v | z | b | a
    e->qkv = gpu_alloc(e, (uint64_t)(e->n_heads + 2 * e->n_kv + (e->qwen35 ? e->n_heads : 0)) * e->head_dim * 4);
    e->lin = gpu_alloc(e, (e->conv_n + (uint64_t)e->n_vh * e->dv + 2 * e->n_vh + 64) * 4);
    e->o = gpu_alloc(e, e->hidden * 4);
    e->gu = gpu_alloc(e, 2 * e->inter * 4);
    e->down = gpu_alloc(e, e->hidden * 4);
    e->logits = gpu_alloc(e, (uint64_t)(e->head_idx ? e->t[e->head_idx].rows : e->vocab) * 4);
    if (e->swar) {   // int8 activations for up to PF_ROWS rows of the widest input, and the split-K partial sums
        uint32_t mc = 0, mr = 0;
        for (uint32_t i = 0; i < e->n_tensors; i++)
            if (e->t[i].fmt == 6 || e->t[i].fmt == 8) {
                if (e->t[i].cols > mc) mc = e->t[i].cols;
                if (e->t[i].rows > mr && e->t[i].rows <= SWAR_SPLIT_ROWS) mr = e->t[i].rows;
            }
        e->x8 = gpu_alloc(e, (uint64_t)PF_ROWS * mc * 4);
        e->dx8 = gpu_alloc(e, (uint64_t)PF_ROWS * mc / 32 * 4);
        e->init8 = gpu_alloc(e, (uint64_t)PF_ROWS * mc / 32 * 4);
        e->yp_rows = mr;
        e->yp = gpu_alloc(e, 16ull * mr * 4);
    }
    e->last = gpu_alloc(e, 256);
    e->history = gpu_alloc(e, 4096 * 4);
    // the batched prompt: q4i / q3i matrices (rows in tiles of 64), and PF_ROWS rows of each buffer (5.8 MiB for the 9B)
    uint64_t pf_wa = 0, pf_wh = 0;
    for (uint32_t i = 0; e->pf && i < e->n_layers; i++) {
        uint32_t b = e->layer_base[i], full = e->full_mask >> i & 1;
        uint32_t m[4] = {b + (full ? 4 : 2), b + (full ? 5 : 7), b + (full ? 6 : 8), b + (full ? 7 : 9)};
        for (int j = 0; j < 4; j++) {
            const Tensor* t = &e->t[m[j]];
            int sw = t->fmt == 6 || t->fmt == 8;
            if ((t->fmt != 3 && t->fmt != 5 && !sw) || t->rows % (sw ? 128 : 64) || t->cols % 32)
                e->pf = 0;
            if (!(j & 1) && t->rows > pf_wa) pf_wa = t->rows;   // input projection, gate|up: rows out
            if ((j & 1) && t->cols > pf_wh) pf_wh = t->cols;    // o / out projection, down: columns in
        }
    }
    pf_a_floats = (uint64_t)PF_ROWS * pf_wa, pf_h_floats = (uint64_t)PF_ROWS * pf_wh;
    if (e->pf) {
        e->pf_x = gpu_alloc(e, (uint64_t)PF_ROWS * e->hidden * 4);
        e->pf_xn = gpu_alloc(e, (uint64_t)PF_ROWS * e->hidden * 4);
        e->pf_a = gpu_alloc(e, PF_ROWS * pf_wa * 4);
        e->pf_h = gpu_alloc(e, PF_ROWS * pf_wh * 4);
        if (!e->pf_h) e->pf = 0;
    }
    e->model = cpu_alloc(e, e->weights_size);
    if (!e->model) {
        fail(e, "model does not fit in %u MiB", (unsigned)(e->mem_size >> 20));
        return -1;
    }

    int f = open(e->weights_path, O_RDONLY);
    if (f < 0) {
        fail(e, "cannot open %s", e->weights_path);
        return -1;
    }
    uint64_t done = 0, chunk = 32ull << 20;
    while (done < e->weights_size) {
        uint64_t n = e->weights_size - done < chunk ? e->weights_size - done : chunk;
        ssize_t r = read(f, (uint8_t*)(uintptr_t)e->model + done, n);
        if (r <= 0) break;
        done += r;
        if (progress) progress(user, (float)done / e->weights_size, "Cargando el modelo");
    }
    close(f);
    if (done != e->weights_size) {
        fail(e, "weights file incomplete (%llu of %llu bytes)", (unsigned long long)done,
             (unsigned long long)e->weights_size);
        return -1;
    }
    say("Garlic model weights loaded.");

    /* Tensor payloads live in one CPU file image, but each tensor is a separate Vulkan buffer.
       This preserves the original Garlic weight offsets while respecting maxMemoryAllocationSize. */
    MatrixClRuntime* modelRuntime = (MatrixClRuntime*)e->android_cl_runtime;
    for (uint32_t i = 0; i < e->n_tensors; ++i) {
        const Tensor* tensor = &e->t[i];
        if (tensor->size == 0 || tensor->off > e->weights_size ||
            tensor->size > e->weights_size - tensor->off) {
            fail(e, "Tensor %u points outside the Garlic weights file.", i);
            return -1;
        }
        if ((e->cpu_embed && i == 0) || tensor->fmt == 7) continue;
        if (tensor->size > matrix_cl_max_allocation(modelRuntime)) {
            fail(e, "Tensor %u (%llu bytes) exceeds this GPU's maximum single allocation.",
                 i, (unsigned long long)tensor->size);
            return -1;
        }
        const uintptr_t host = (uintptr_t)e->model + tensor->off;
        char error[256] = {0};
        if (matrix_cl_register_region(modelRuntime, host, (size_t)tensor->size,
                                       (const void*)host, error, sizeof(error)) != 0) {
            fail(e, "Cannot upload Garlic tensor %u: %s", i,
                 error[0] ? error : "OpenCL buffer registration failed");
            return -1;
        }
    }

    if (e->head_idx && e->head_idx != e->n_tensors - 1) {   // the row -> token id table (trimmed lm_head)
        const Tensor* t = &e->t[e->n_tensors - 1];
        e->ids = malloc((size_t)t->rows * 4);
        int fi = open(e->weights_path, O_RDONLY);
        int ok = e->ids && fi >= 0 && pread(fi, e->ids, (size_t)t->rows * 4, (off_t)t->off) == (ssize_t)t->rows * 4;
        if (fi >= 0) close(fi);
        if (!ok) {
            fail(e, "cannot read the vocabulary table");
            return -1;
        }
    }
    if (e->cpu_embed) {   // the input embedding stays in the file; keep it open and cache rows
        const Tensor* t = &e->t[0];
        e->fd = open(e->weights_path, O_RDONLY);
        e->row_cache = malloc((size_t)ROW_CACHE * (t->cols / 2 + t->cols / 16));
        e->row_tag = malloc(ROW_CACHE * sizeof(int32_t));
        if (e->fd < 0 || !e->row_cache || !e->row_tag) {
            fail(e, "cannot set up the embedding rows");
            return -1;
        }
        for (int i = 0; i < ROW_CACHE; i++) e->row_tag[i] = -1;
    }
    mm_tune(e);
    swar_tune(e);
    engine_reset(e);

    return 0;
}

// Dequantized embedding row of token `tok` ("q4row": nibbles as q4, then fp16 scales) into dst.
static int fetch_row(Engine* e, uint32_t tok, float* dst) {
    const Tensor* t = &e->t[0];
    uint32_t cols = t->cols, rb = cols / 2 + cols / 16, slot = tok & (ROW_CACHE - 1);
    uint8_t* row = e->row_cache + (size_t)slot * rb;
    if (e->row_tag[slot] != (int32_t)tok) {
        if (lseek(e->fd, (off_t)(t->off + (uint64_t)tok * rb), SEEK_SET) < 0 || read(e->fd, row, rb) != (ssize_t)rb) {
            fail(e, "cannot read the embedding row of token %u", tok);
            return -1;
        }
        e->row_tag[slot] = (int32_t)tok;
    }
    const uint16_t* sc = (const uint16_t*)(row + cols / 2);
    for (uint32_t b = 0; b < cols / 32; b++) {
        uint32_t h = sc[b], ex = (h >> 10) & 31;
        uint32_t f = ex ? ((h & 0x8000u) << 16) | ((ex + 112u) << 23) | ((h & 0x3FFu) << 13) : (h & 0x8000u) << 16;
        float d;
        memcpy(&d, &f, 4);
        const uint8_t* q = row + b * 16;
        for (int j = 0; j < 16; j++) {
            dst[b * 32 + j] = ((float)(q[j] & 15) - 8.0f) * d;
            dst[b * 32 + 16 + j] = ((float)(q[j] >> 4) - 8.0f) * d;
        }
    }
    const int uploadStatus = matrix_cl_write_address((MatrixClRuntime*)e->android_cl_runtime,
                                                       (uintptr_t)dst, (size_t)cols * sizeof(float), dst);
    if (uploadStatus != 0) {
        fail(e, "Could not upload CPU embedding row to the GPU (%d).", uploadStatus);
        return -1;
    }
    return 0;
}

uint64_t engine_kv_lend(Engine* e, uint64_t* size) {
    if (!e->kv_hi) return *size = 0, 0;
    e->ctx_limit = e->lend_ctx;
    *size = e->kv_split;
    return e->kv_hi;
}

void engine_kv_return(Engine* e) { e->ctx_limit = e->max_seq; }

void engine_reset(Engine* e) {
    for (uint32_t i = 0; i < e->n_layers; i++) {
        if (!(e->full_mask >> i & 1)) {
            memset((void*)(uintptr_t)e->kc[i], 0, e->conv_n * 12);
            memset((void*)(uintptr_t)e->vc[i], 0, e->state_bytes);
                    if (e->android_cl_runtime) {
                matrix_cl_write_address((MatrixClRuntime*)e->android_cl_runtime,
                                        (uintptr_t)e->kc[i], (size_t)(e->conv_n * 12),
                                        (void*)(uintptr_t)e->kc[i]);
                matrix_cl_write_address((MatrixClRuntime*)e->android_cl_runtime,
                                        (uintptr_t)e->vc[i], (size_t)e->state_bytes,
                                        (void*)(uintptr_t)e->vc[i]);
            }
        }
    }
}

// ---- command buffer ----------------------------------------------------------------------------------------------

static void put(Engine* e, uint32_t w) {
    if (e->ndw < e->dcb_cap) e->dcb[e->ndw] = w;
    e->ndw++;
}

static void pkt3(Engine* e, uint32_t op, int n, int compute) {
    put(e, (3u << 30) | ((uint32_t)(n - 1) << 16) | (op << 8) | (compute ? 2 : 0));
}

static void set_sh(Engine* e, uint32_t reg, int n, const uint32_t* v) {
    pkt3(e, 0x76, n + 1, 0);
    put(e, reg);
    for (int i = 0; i < n; i++) put(e, v[i]);
}

static void set_sh1(Engine* e, uint32_t reg, uint32_t a) { set_sh(e, reg, 1, &a); }

// ACQUIRE_MEM over all memory: the cache actions in cntl (CP_COHER_CNTL), waiting for them to finish.
static void acquire(Engine* e, uint32_t cntl) {
    pkt3(e, 0x58, 6, 0);
    put(e, cntl);
    put(e, 0xFFFFFFFF);
    put(e, 0);
    put(e, 0);
    put(e, 0);
    put(e, 0x0A);
}

static void eop(Engine* e, uint32_t event, uint64_t addr, uint32_t data_sel, uint32_t value) {
    pkt3(e, 0x47, 5, 0);
    put(e, event);
    put(e, (uint32_t)addr);
    put(e, (uint32_t)((addr >> 32) & 0xFFFF) | (data_sel << 29));
    put(e, value);
    put(e, 0);
}

// Kernel arguments as the compiler lays out the C struct: 'p' pointer (8 bytes), 'u' uint, 'f' float.
static uint64_t args_v(Engine* e, const char* spec, ...) {
    uint32_t off = (e->kargs_used + 63) & ~63u;
    uint8_t* header = e->kargs + off;
    if (off + 64u > e->kargs_cap) {
        fail(e, "Garlic OpenCL argument buffer overflow.");
        return 0;
    }
    memset(header, 0, 64);
    snprintf((char*)header, 64, "%s", spec);
    uint8_t* p = header + 64;
    uint32_t pos = 0;
    va_list ap;
    va_start(ap, spec);
    for (const char* s = spec; *s; s++) {
        if (*s == 'p') {
            pos = (pos + 7) & ~7u;
            uint64_t v = va_arg(ap, uint64_t);
            if (off + 64u + pos + 8u > e->kargs_cap) {
                va_end(ap);
                fail(e, "Garlic OpenCL argument buffer overflow.");
                return 0;
            }
            memcpy(p + pos, &v, 8);
            pos += 8;
        } else if (*s == 'u') {
            uint32_t v = va_arg(ap, uint32_t);
            if (off + 64u + pos + 4u > e->kargs_cap) {
                va_end(ap);
                fail(e, "Garlic OpenCL argument buffer overflow.");
                return 0;
            }
            memcpy(p + pos, &v, 4);
            pos += 4;
        } else {
            float v = (float)va_arg(ap, double);
            if (off + 64u + pos + 4u > e->kargs_cap) {
                va_end(ap);
                fail(e, "Garlic OpenCL argument buffer overflow.");
                return 0;
            }
            memcpy(p + pos, &v, 4);
            pos += 4;
        }
    }
    va_end(ap);
    e->kargs_used = off + 64u + pos;
    return (uint64_t)(uintptr_t)p;
}

// args(e, "spec", values...): the spec must name every value passed, checked when compiling. A spec with one letter
// too many would make va_arg read stack garbage into a kernel's arguments.
#define NARGS_(_1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16, N, ...) N
#define NARGS(...) NARGS_(__VA_ARGS__, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
#define args(e, spec, ...) \
    (sizeof(char[sizeof(spec) - 1 == NARGS(__VA_ARGS__) ? 1 : -1]) ? args_v(e, spec, __VA_ARGS__) : 0)

static void dispatch(Engine* e, const GpuKernel* k, uint64_t kargs, uint32_t groups, uint32_t threads) {
    if (!k || !k->android_handle || !e->android_cl_runtime ||
        groups == 0 || threads == 0) {
        fail(e, "Invalid Garlic OpenCL dispatch.");
        return;
    }
    const uint8_t* payload = (const uint8_t*)(uintptr_t)kargs;
    const char* spec = (const char*)(payload - 64);
    if (!spec[0]) {
        fail(e, "Garlic OpenCL dispatch has no argument signature.");
        return;
    }
    size_t position = 0;
    uint32_t argument = 0;
    MatrixClRuntime* runtime = (MatrixClRuntime*)e->android_cl_runtime;
    for (const char* type = spec; *type; ++type, ++argument) {
        int status = 0;
        if (*type == 'p') {
            position = (position + 7u) & ~7u;
            uint64_t address = 0;
            memcpy(&address, payload + position, sizeof(address));
            position += sizeof(address);
            MatrixClBuffer buffer = NULL;
            if (address) {
                char error[256] = {0};
                buffer = matrix_cl_lookup_region(runtime, (uintptr_t)address, NULL,
                                                 error, sizeof(error));
                if (!buffer) {
                    fail(e, "Garlic kernel %s: %s", k->name,
                         error[0] ? error : "pointer is not backed by a Vulkan buffer");
                    return;
                }
            }
            status = matrix_cl_set_mem_arg(runtime, (MatrixClKernel)k->android_handle, argument, buffer);
        } else if (*type == 'u') {
            uint32_t value = 0;
            memcpy(&value, payload + position, sizeof(value));
            position += sizeof(value);
            status = matrix_cl_set_arg(runtime, (MatrixClKernel)k->android_handle,
                                       argument, sizeof(value), &value);
        } else {
            float value = 0.0f;
            memcpy(&value, payload + position, sizeof(value));
            position += sizeof(value);
            status = matrix_cl_set_arg(runtime, (MatrixClKernel)k->android_handle,
                                       argument, sizeof(value), &value);
        }
        if (status != 0) {
            fail(e, "OpenCL argument %u for Garlic kernel %s failed (%d).", argument, k->name, status);
            return;
        }
    }

    const int status = matrix_cl_enqueue(runtime, (MatrixClKernel)k->android_handle, groups, threads);
    if (status != 0) {
        fail(e, "OpenCL dispatch for Garlic kernel %s failed (%d).", k->name, status);
    }
}

static uint64_t W(Engine* e, uint32_t idx) { return (uint64_t)(uintptr_t)e->model + e->t[idx].off; }

uint32_t engine_token_of(const Engine* e, uint32_t row) { return e->ids && row < e->vocab ? e->ids[row] : row; }

int32_t engine_row_of(const Engine* e, uint32_t token) {
    if (!e->ids) return token < e->vocab ? (int32_t)token : -1;
    for (uint32_t r = 0; r < e->vocab; r++)
        if (e->ids[r] == token) return (int32_t)r;
    return -1;
}

#ifdef GARLIC_SPEC
// (the token engine_run or engine_spec_step drew last has its row at hand)
static int32_t engine_row_of_fast(const Engine* e, uint32_t token) {
    if (e->ids && e->last_row < e->vocab && e->ids[e->last_row] == token) return (int32_t)e->last_row;
    return engine_row_of(e, token);
}
#endif

// ---- SWAR products on q4s weights (shaders/spec.h) ----------------------------------------------------------------
// Y[t] (+)= W X[t] for n rows of X (row-major, cols floats each) into Y rows of t->rows: the activations go to int8
// per block of 32 once (quant_x8), then the weights are read once per group of up to 4 rows (swar_mv<n>), with the
// variant swar_tune found fastest for this shape and count (split-K ones add their parts with sum_split).
// gate (0 = none): a GPU word; when it is not 0 the kernels do nothing (the draft chain after it stopped).
static const uint32_t swar_waves[5] = {4, 8, 16, 8, 8}, swar_split[5] = {1, 1, 1, 2, 4};

int swar_fixed = -1;   // (tests: >= 0 forces one variant for every shape and token count, so n tokens sum like 1)

static int swar_variant(Engine* e, const Tensor* t, uint32_t n) {
    if (swar_fixed >= 0) return swar_fixed;
    for (int i = 0; i < e->swar_n; i++)
        if (e->swar_tab[i].rows == t->rows && e->swar_tab[i].cols == t->cols && e->swar_tab[i].fmt == t->fmt)
            return e->swar_tab[i].v[n - 1];
    return t->rows >= 16384 ? 0 : 1;   // (before swar_tune: w4 for tall matrices, else w8)
}

static void swar_chunk(Engine* e, const Tensor* t, uint64_t w, int v, uint32_t c, uint32_t t0, uint64_t Y,
                       uint32_t acc, uint64_t gate) {
    uint32_t nb = t->cols / 32, S = swar_split[v];
    if (S > 1 && t->rows > e->yp_rows) S = 1, v = 1;
    uint64_t y = Y + (uint64_t)t0 * t->rows * 4;
    dispatch(e, (t->fmt == 8 ? e->k_swar3 : e->k_swar)[c - 1][v],
             args(e, "ppppppuuup", w, w + t->scales, e->x8 + (uint64_t)t0 * t->cols * 4, e->dx8 + (uint64_t)t0 * nb * 4,
                  e->init8 + (uint64_t)t0 * nb * 4, S > 1 ? e->yp : y, t->cols, t->rows, acc, gate),
             t->rows / 128 * S, 64 * swar_waves[v]);
    if (S > 1)
        dispatch(e, e->k_sumsplit, args(e, "ppuuup", e->yp, y, c * t->rows, S, acc, gate), (c * t->rows + 255) / 256, 256);
}

static void swar_mm(Engine* e, uint32_t idx, uint64_t X, uint64_t Y, uint32_t n, uint32_t acc, uint64_t gate) {
    const Tensor* t = &e->t[idx];
    uint64_t w = W(e, idx);
    uint32_t nb = t->cols / 32;
    dispatch(e, e->k_qx8, args(e, "ppppup", X, e->x8, e->dx8, e->init8, n * nb, gate), (n * nb + 255) / 256, 256);
    for (uint32_t t0 = 0; t0 < n; t0 += 4) {
        uint32_t c = n - t0 < 4 ? n - t0 : 4;
        swar_chunk(e, t, w, swar_variant(e, t, c), c, t0, Y, acc, gate);
    }
}

static void matvec(Engine* e, uint32_t idx, uint64_t x, uint64_t y) {
    const Tensor* t = &e->t[idx];
    uint64_t w = W(e, idx);
    if (t->fmt == 6 || t->fmt == 8) {
        swar_mm(e, idx, x, y, 1, 0, 0);
        return;
    }
    if (t->fmt == 3 || t->fmt == 5) {   // q4i / q3i: block sums of x, then one lane per row (4 waves per tile
        #ifdef MATRIX_ANDROID
        uint32_t waves = 4;
#else
        uint32_t waves = t->rows < 4096 || t->cols < 4096 ? 8 : 4;   // of 64 rows, 8 for small matrices)
#endif
        const GpuKernel* k = t->fmt == 5 ? (waves == 8 ? e->k_mv38 : e->k_mv3) : (waves == 8 ? e->k_mvi8 : e->k_mvi);
        dispatch(e, e->k_bsum, args(e, "ppu", x, e->bsum, t->cols), (t->cols / 32 + 255) / 256, 256);
        dispatch(e, k, args(e, "pppppu", w, w + t->scales, x, e->bsum, y, t->cols), t->rows / 64, 64 * waves);
        return;
    }
    dispatch(e, e->k_mv, args(e, "ppppu", w, w + t->scales, x, y, t->cols), t->rows / 4, 64);
}

static void step(Engine* e, uint32_t pos, uint32_t forced, uint32_t slot, int sample, float inv_temp,
                 uint32_t seed) {
    if (e->cpu_embed) {   // the CPU put this token's row in staging slot `slot`
        uint64_t src = e->staging + (uint64_t)slot * e->hidden * 4;
        dispatch(e, e->k_copy, args(e, "ppu", src, e->x, e->hidden), e->hidden / 256, 256);
    } else {
        uint64_t emb = W(e, 0);
        dispatch(e, e->k_embed, args(e, "pppupu", emb, emb + e->t[0].scales, e->last, forced, e->x, e->hidden),
                 1, 256);
    }
    for (uint32_t i = 0; i < e->n_layers; i++) {
        // full attention: attn_norm, mlp_norm, q_norm, k_norm, wqkv, wo, wgu, wdown
        // Gated DeltaNet:  attn_norm, mlp_norm, w_in, conv, A, dt_bias, gnorm, wout, wgu, wdown
        uint32_t b = e->layer_base[i];
        int full = e->full_mask >> i & 1;
        uint32_t mlp = b + (full ? 6 : 8);
        uint64_t kc = e->kc[i], vc = e->vc[i];
        dispatch(e, e->k_rmsnorm, args(e, "pppuf", e->x, W(e, b), e->xn, e->hidden, (double)e->eps), 1, 256);
        if (!full) {
            uint32_t vd = e->n_vh * e->dv;
            matvec(e, b + 2, e->xn, e->lin);
            dispatch(e, e->k_conv, args(e, "pppu", e->lin, W(e, b + 3), kc, (uint32_t)e->conv_n),
                     (uint32_t)(e->conv_n + 255) / 256, 256);
            dispatch(e, e->k_delta,
                     args(e, "ppppppuuf", e->lin, W(e, b + 4), W(e, b + 5), vc, W(e, b + 6), e->attn, e->n_kh,
                          e->n_vh, (double)e->eps),
                     e->n_vh, 256);
            matvec(e, b + 7, e->attn, e->o);
            (void)vd;
            goto mlp_block;
        }
        matvec(e, b + 4, e->xn, e->qkv);
        // attention in blocks of 64 positions (one group per block and kv head), then merge the blocks
        uint32_t blocks = pos / ATT_BLOCK + 1;
        double sc = 1.0 / sqrt((double)e->head_dim);
        if (e->tq) {   // TurboQuant cache: q rotated, k and v in 4 bits; the merge (always) rotates the output back
            // (tq_split: where positions 8192.. are; an older kernels.bin ignores the last argument)
            dispatch(e, e->k_qk_tq,
                     args(e, "pppppppuuufp", e->qkv, W(e, b + 2), W(e, b + 3), e->rope, kc, vc, e->tq_tab, pos,
                          e->n_heads, e->n_kv, (double)e->eps, e->tq_split),
                     e->n_heads + 2 * e->n_kv, 64);
            dispatch(e, e->k_attn_tq,
                     args(e, "pppppuuufp", e->qkv, kc, vc, e->attn_part, e->tq_tab, pos, e->n_heads, e->n_kv, sc,
                          e->tq_split),
                     blocks * e->n_kv, 256);
            dispatch(e, e->k_comb_tq, args(e, "pppuu", e->attn_part, e->attn, e->tq_tab, e->n_heads, blocks),
                     e->n_heads, 256);
        } else {
            // (Qwen3.5: one more argument, where the second half of the cache is)
            dispatch(e, e->k_qk,
                     e->qwen35 ? args(e, "ppppppuuufp", e->qkv, W(e, b + 2), W(e, b + 3), e->rope, kc, vc, pos,
                                      e->n_heads, e->n_kv, (double)e->eps, e->kv_split)
                               : args(e, "ppppppuuuf", e->qkv, W(e, b + 2), W(e, b + 3), e->rope, kc, vc, pos,
                                      e->n_heads, e->n_kv, (double)e->eps),
                     e->n_heads + 2 * e->n_kv, 64);
            // a context of one block is finished by attn_part itself
            dispatch(e, e->k_attn,
                     e->qwen35 ? args(e, "pppppuuufp", e->qkv, kc, vc, e->attn_part, e->attn, pos, e->n_heads, e->n_kv,
                                      sc, e->kv_split)
                               : args(e, "pppppuuuf", e->qkv, kc, vc, e->attn_part, e->attn, pos, e->n_heads, e->n_kv,
                                      sc),
                     blocks * e->n_kv, 256);
            if (blocks > 1)
                dispatch(e, e->k_attn_comb, args(e, "ppuu", e->attn_part, e->attn, e->n_heads, blocks), e->n_heads,
                         e->head_dim);
        }
        if (e->qwen35) {   // output gate: attn *= sigmoid(gate), the gate rows follow q, k, v in the projection
            uint32_t n = e->n_heads * e->head_dim;
            uint64_t gate = e->qkv + (uint64_t)(e->n_heads + 2 * e->n_kv) * e->head_dim * 4;
            dispatch(e, e->k_sig, args(e, "ppu", e->attn, gate, n), (n + 255) / 256, 256);
        }
        matvec(e, b + 5, e->attn, e->o);
    mlp_block:
        dispatch(e, e->k_add, args(e, "ppu", e->x, e->o, e->hidden), e->hidden / 256, 256);
        dispatch(e, e->k_rmsnorm, args(e, "pppuf", e->x, W(e, b + 1), e->xn, e->hidden, (double)e->eps), 1, 256);
        matvec(e, mlp, e->xn, e->gu);
        dispatch(e, e->k_silu, args(e, "ppu", e->gu, e->hmlp, e->inter), (e->inter + 255) / 256, 256);
        matvec(e, mlp + 1, e->hmlp, e->down);
        dispatch(e, e->k_add, args(e, "ppu", e->x, e->down, e->hidden), e->hidden / 256, 256);
    }
    if (sample) {
        dispatch(e, e->k_rmsnorm, args(e, "pppuf", e->x, W(e, 1), e->xn, e->hidden, (double)e->eps), 1, 256);
        matvec(e, e->head_idx, e->xn, e->logits);
        // min-p 0.05: drop tokens under 5 % of the top token's probability (gap T * ln 20 in logits)
        double min_gap = inv_temp > 0.0f ? 2.9957 / inv_temp : 0.0;
        dispatch(e, e->k_sample, args(e, "pufuppuf", e->logits, e->vocab, (double)inv_temp, seed, e->last, e->history, 0,
                                      min_gap),
                 1, 256);
    }
}

// ---- the prompt in batches (shaders/prefill.h) -------------------------------------------------------------------
// The same operations as n calls of step(), layer by layer for all the tokens: each matrix is read and unpacked once
// for up to 32 tokens (matrix x matrix), each DeltaNet state is loaded and stored once per batch, and the attention
// runs token by token over the cache with the decode kernels. Residual adds are folded into the products.

// Y (+)= W X for n rows of X (q4i or q3i), with the kernel mm_tune found fastest for this shape (else the plain one).
static void mm_with(Engine* e, const GpuKernel* k, uint32_t r, uint32_t idx, uint64_t X, uint64_t Y, uint32_t n,
                    uint32_t accumulate) {
    const Tensor* t = &e->t[idx];
    uint64_t w = W(e, idx);
    dispatch(e, k, args(e, "ppppuuuu", w, w + t->scales, X, Y, t->cols, t->rows, n, accumulate),
             t->rows / 64 * ((n + r - 1) / r), 256);
}

static void mmr(Engine* e, uint32_t idx, uint64_t X, uint64_t Y, uint32_t n, uint32_t accumulate) {
    const Tensor* t = &e->t[idx];
    if (t->fmt == 6 || t->fmt == 8) {
        swar_mm(e, idx, X, Y, n, accumulate, 0);
        return;
    }
    for (int i = 0; i < e->pf_nmm; i++)
        if (e->pf_mm[i].fmt == t->fmt && e->pf_mm[i].rows == t->rows && e->pf_mm[i].cols == t->cols) {
            int s = n <= 16;
            mm_with(e, e->pf_mm[i].k[s], e->pf_mm[i].r[s], idx, X, Y, n, accumulate);
            return;
        }
    mm_with(e, t->fmt == 5 ? e->k_mm3r : e->k_mm4r, 16, idx, X, Y, n, accumulate);
}

// n (<= PF_ROWS) tokens at positions pos0.., their embedding rows at `stage` (the CPU put them there, cpu_embed) or
// dequantized from the table in GPU memory (the 4B: one embed dispatch per token, as step() does); with `sample`, the
// token after the last one is drawn as in step().
static void prefill(Engine* e, uint64_t stage, const uint32_t* tokens, uint32_t n, uint32_t pos0, int sample,
                    float inv_temp, uint32_t seed) {
    uint32_t H = e->hidden;
    if (e->cpu_embed) {
        dispatch(e, e->k_copy, args(e, "ppu", stage, e->pf_x, n * H), n * H / 256, 256);
    } else {
        uint64_t emb = W(e, 0);
        for (uint32_t r = 0; r < n; r++)
            dispatch(e, e->k_embed, args(e, "pppupu", emb, emb + e->t[0].scales, e->last, tokens[r],
                                         e->pf_x + (uint64_t)r * H * 4, H), 1, 256);
    }
    for (uint32_t i = 0; i < e->n_layers; i++) {
        uint32_t b = e->layer_base[i];
        int full = e->full_mask >> i & 1;
        uint32_t mlp = b + (full ? 6 : 8);
        uint64_t kc = e->kc[i], vc = e->vc[i];
        dispatch(e, e->k_rmsr, args(e, "pppuf", e->pf_x, W(e, b), e->pf_xn, H, (double)e->eps), n, 256);
        if (!full) {   // Gated DeltaNet: input projection, conv + delta rule over the tokens in order, out projection
            uint32_t ld = e->t[b + 2].rows;
            mmr(e, b + 2, e->pf_xn, e->pf_a, n, 0);
            dispatch(e, e->k_convr, args(e, "pppuuu", e->pf_a, W(e, b + 3), kc, (uint32_t)e->conv_n, ld, n),
                     (uint32_t)(e->conv_n + 255) / 256, 256);
            dispatch(e, e->k_deltar,
                     args(e, "ppppppuufuu", e->pf_a, W(e, b + 4), W(e, b + 5), vc, W(e, b + 6), e->pf_h, e->n_kh,
                          e->n_vh, (double)e->eps, ld, n),
                     e->n_vh, 256);
            mmr(e, b + 7, e->pf_h, e->pf_x, n, 1);
        } else {       // full attention: q | k | v | gate for all, then norms + RoPE + cache and attention per token
            uint32_t ld = e->t[b + 4].rows, nq = e->n_heads * e->head_dim, G = e->n_heads + 2 * e->n_kv;
            double sc = 1.0 / sqrt((double)e->head_dim);
            mmr(e, b + 4, e->pf_xn, e->pf_a, n, 0);
            for (uint32_t r = 0; r < n; r++) {
                uint64_t q = e->pf_a + (uint64_t)r * ld * 4;
                if (e->tq)
                    dispatch(e, e->k_qk_tq,
                             args(e, "pppppppuuufp", q, W(e, b + 2), W(e, b + 3), e->rope, kc, vc, e->tq_tab, pos0 + r,
                                  e->n_heads, e->n_kv, (double)e->eps, e->tq_split),
                             G, 64);
                else
                    dispatch(e, e->k_qk,
                             args(e, "ppppppuuufp", q, W(e, b + 2), W(e, b + 3), e->rope, kc, vc, pos0 + r, e->n_heads,
                                  e->n_kv, (double)e->eps, e->kv_split),
                             G, 64);
            }
            for (uint32_t r = 0; r < n; r++) {   // token r sees positions up to its own (all written above)
                uint64_t q = e->pf_a + (uint64_t)r * ld * 4, out = e->pf_h + (uint64_t)r * nq * 4;
                uint32_t pos = pos0 + r, blocks = pos / ATT_BLOCK + 1;
                if (e->tq) {
                    dispatch(e, e->k_attn_tq,
                             args(e, "pppppuuufp", q, kc, vc, e->attn_part, e->tq_tab, pos, e->n_heads, e->n_kv, sc,
                                  e->tq_split),
                             blocks * e->n_kv, 256);
                    dispatch(e, e->k_comb_tq, args(e, "pppuu", e->attn_part, out, e->tq_tab, e->n_heads, blocks),
                             e->n_heads, 256);
                } else {
                    dispatch(e, e->k_attn,
                             args(e, "pppppuuufp", q, kc, vc, e->attn_part, out, pos, e->n_heads, e->n_kv, sc,
                                  e->kv_split),
                             blocks * e->n_kv, 256);
                    if (blocks > 1)
                        dispatch(e, e->k_attn_comb, args(e, "ppuu", e->attn_part, out, e->n_heads, blocks), e->n_heads,
                                 e->head_dim);
                }
            }
            uint64_t gate = e->pf_a + (uint64_t)(e->n_heads + 2 * e->n_kv) * e->head_dim * 4;
            dispatch(e, e->k_sigr, args(e, "ppuuu", e->pf_h, gate, nq, ld, n), (n * nq + 255) / 256, 256);
            mmr(e, b + 5, e->pf_h, e->pf_x, n, 1);
        }
        dispatch(e, e->k_rmsr, args(e, "pppuf", e->pf_x, W(e, b + 1), e->pf_xn, H, (double)e->eps), n, 256);
        mmr(e, mlp, e->pf_xn, e->pf_a, n, 0);
        dispatch(e, e->k_silur, args(e, "ppuu", e->pf_a, e->pf_h, e->inter, n), (n * e->inter + 255) / 256, 256);
        mmr(e, mlp + 1, e->pf_h, e->pf_x, n, 1);
    }
    if (sample) {   // as step(): the last token's final norm, the logits and the pick
        uint64_t xl = e->pf_x + (uint64_t)(n - 1) * H * 4;
        dispatch(e, e->k_rmsnorm, args(e, "pppuf", xl, W(e, 1), e->xn, H, (double)e->eps), 1, 256);
        matvec(e, e->head_idx, e->xn, e->logits);
        double min_gap = inv_temp > 0.0f ? 2.9957 / inv_temp : 0.0;
        dispatch(e, e->k_sample, args(e, "pufuppuf", e->logits, e->vocab, (double)inv_temp, seed, e->last, e->history, 0,
                                      min_gap),
                 1, 256);
    }
}

// submit = submit_go (the command buffer to the GPU) + submit_wait (until its end-of-pipe label); between the two the
// CPU may prepare the next submission's inputs (not the command buffer, which the GPU is reading).
static int submit_go(Engine* e) {
    // dispatch() already enqueued kernels on clvk's in-order command queue.
    return e->error[0] ? -1 : 0;
}

static int submit_wait(Engine* e) {
    MatrixClRuntime* runtime = (MatrixClRuntime*)e->android_cl_runtime;
    if (!runtime) {
        fail(e, "Garlic OpenCL memory pool is not ready.");
        return -1;
    }
    struct timespec before, after;
    clock_gettime(CLOCK_MONOTONIC, &before);
    int status = matrix_cl_finish(runtime);
    clock_gettime(CLOCK_MONOTONIC, &after);
    if (status != 0) {
        fail(e, "Vulkan/OpenCL GPU queue failed (%d).", status);
        return -1;
    }
    status = matrix_cl_read_address(runtime, (uintptr_t)e->last, sizeof(uint32_t),
                                    (void*)(uintptr_t)e->last);
    if (status != 0) {
        fail(e, "Cannot read Garlic's sampled token from the GPU (%d).", status);
        return -1;
    }
    e->last_gpu_seconds = (double)(after.tv_sec - before.tv_sec) +
                          (double)(after.tv_nsec - before.tv_nsec) / 1.0e9;
    return 0;
}

static int submit(Engine* e) { return submit_go(e) || submit_wait(e) ? -1 : 0; }

static void begin(Engine* e) {
    e->kargs_used = 0;
}

// Start-up check of what the engine relies on, with the "add" kernel every kernels.bin has: dispatches that read what
// the previous one wrote (L1 / scalar cache dropped in between), results written back for the CPU before the label, and
// a second submission after the CPU rewrote the inputs at the same addresses (L2 invalidated when a submission starts).
// Only tested on one console (FW 11.52); on another model or firmware a difference there would give nonsense answers
// instead of an error. Takes well under a millisecond; the buffers are given back.
// /data/garlic_selftest.txt = 0 skips it.
static int gpu_selftest(Engine* e) {
    // Android backend runs a native clvk probe and an actual Garlic shader probe before
    // loading the model. Do not execute the PS4 command-buffer self-test here.
    (void)e;
    return 0;
}

// The Gated DeltaNet layers' state (conv and delta rule), copied on the GPU to buf (save 1) or back from it (save 0).
// With the attention cache, which is written by position, that is all a conversation needs to go back to an earlier
// position: main.c takes a turn's reasoning out of the history this way ("Pensar"). 50.25 MiB for the 9B.
uint64_t engine_state_size(const Engine* e) {
    uint64_t n = 0;
    for (uint32_t i = 0; i < e->n_layers; i++)
        if (!(e->full_mask >> i & 1)) n += ((e->conv_n * 12 + 255) & ~255ull) + ((e->state_bytes + 255) & ~255ull);
#ifdef GARLIC_SPEC
    if (spec_carry()) n += (uint64_t)e->hidden * 4;   // the MTP head's carry (h before the next token)
#endif
    return n;
}

int engine_state_copy(Engine* e, uint64_t buf, int save) {
    (void)e; (void)buf; (void)save;
    return -1; // State snapshots need a separate Android-safe host/device copy API.
}

int engine_run(Engine* e, const uint32_t* tokens, int n, int pos0, float temperature, uint32_t seed, uint32_t* next) {
    if (pos0 + n > (int)e->max_seq || pos0 + n > (int)e->ctx_limit) {
        fail(e, "context full (%d tokens)", pos0 + n);
        return -1;
    }
    float inv_temp = temperature > 0.0f ? 1.0f / temperature : 0.0f;
    double gpu = 0.0;
    // 16K: positions past lend_ctx are in kv_hi, so S3Gen's weights, if they were kept there after voice mode, are
    // about to be overwritten (voice mode reads them again when it starts)
    if (e->tq_split && pos0 + n > (int)e->lend_ctx && e->s3.loaded) e->s3.loaded = 0;
    int batched = e->pf && !e->pf_off && n >= 4;
#ifdef GARLIC_SPEC
    if (spec_active(e)) batched = 1;   // (the MTP head needs the pairs of every position: engine_spec.inc)
#endif
    if (batched) {   // the prompt in batches of PF_ROWS tokens, one submission each
        // two halves of staging: the CPU reads the next batch's embedding rows (from the disk when they are not
        // cached, about as long per row as the GPU takes for a token) while the GPU runs this batch. The first batch
        // is of 8 tokens, so that even a short prompt (voice mode: 20-30 tokens) has most of its rows read meanwhile;
        // with the r8 products a batch of 8 costs about the same per token as one of 32.
#define PF_FIRST 8
#define PF_SIZE(i) ((i) == 0 && n > PF_FIRST ? PF_FIRST : n - (i) < PF_ROWS ? n - (i) : PF_ROWS)
        uint64_t rowb = (uint64_t)e->hidden * 4;
        for (int i = 0; e->cpu_embed && i < PF_SIZE(0); i++)
            if (fetch_row(e, tokens[i], (float*)(uintptr_t)(e->staging + (uint64_t)i * rowb))) return -1;
        for (int i0 = 0, c = 0, k; i0 < n; i0 += k, c ^= 1) {
            k = PF_SIZE(i0);
            int i1 = i0 + k, k1 = i1 < n ? PF_SIZE(i1) : 0;
            begin(e);
            prefill(e, e->staging + (uint64_t)c * PF_ROWS * rowb, tokens + i0, (uint32_t)k, (uint32_t)(pos0 + i0),
                    i0 + k == n, inv_temp, seed);
#ifdef GARLIC_SPEC
            if (spec_active(e)) spec_prefill(e, e->staging + (uint64_t)c * PF_ROWS * rowb, (uint32_t)k, (uint32_t)(pos0 + i0));
#endif
            if (submit_go(e)) return -1;
            uint64_t next_stage = e->staging + (uint64_t)(c ^ 1) * PF_ROWS * rowb;
            for (int i = 0; e->cpu_embed && i < k1; i++)
                if (fetch_row(e, tokens[i1 + i], (float*)(uintptr_t)(next_stage + (uint64_t)i * rowb))) {
                    submit_wait(e);
                    return -1;
                }
            if (submit_wait(e)) return -1;
            gpu += e->last_gpu_seconds;
        }
#undef PF_SIZE
#undef PF_FIRST
        e->last_gpu_seconds = e->pf_seconds = gpu;
        e->last_row = *(volatile uint32_t*)(uintptr_t)e->last;
        *next = engine_token_of(e, e->last_row);
        return 0;
    }
    for (int i0 = 0; i0 < n; i0 += STEPS_PER_SUBMIT) {
        int i1 = i0 + STEPS_PER_SUBMIT < n ? i0 + STEPS_PER_SUBMIT : n;
        begin(e);
        if (e->cpu_embed)
            for (int i = i0; i < i1; i++)
                if (fetch_row(e, tokens[i], (float*)(uintptr_t)(e->staging + (uint64_t)(i - i0) * e->hidden * 4)))
                    return -1;
        for (int i = i0; i < i1; i++) step(e, pos0 + i, tokens[i], (uint32_t)(i - i0), i == n - 1, inv_temp, seed);
        if (submit(e)) return -1;
        gpu += e->last_gpu_seconds;
    }
    e->last_gpu_seconds = gpu;
    e->last_row = *(volatile uint32_t*)(uintptr_t)e->last;
    *next = engine_token_of(e, e->last_row);
    return 0;
}

// For each matrix shape of the model, time the product kernels of shaders/prefill.h for batches of 32 and of 16
// tokens (3 runs each, the input a fixed pattern) and keep the fastest whose result matches mm_q*i_rows (sampled,
// relative difference under 1e-4). ~1 s at start-up; one log line per shape.
static void mm_tune(Engine* e) {
    // The phone-specific runtime selects only the correctness-first four-wave Q4i path.
    // Performance autotuning is deferred until the target Vulkan driver has been profiled.
    (void)e;
}

// For each q4s matrix shape (and the extra shapes spec_load adds) and 1..4 tokens, time the SWAR variants
// (4 / 8 / 16 waves per tile, split-K in 2 or 4 parts; 3 runs each, the input a fixed pattern) and keep the fastest
// whose result matches the first one (sampled, relative difference under 1e-4). One log line per shape.
// t: the shape (rows may be the first rows of a tensor: the draft's 32K rows of lm_head), w its weights. A shape too
// tall for the test buffers (4 rows of output in pf_a: lm_head) is timed on its first rows.
static void swar_tune_shape(Engine* e, const Tensor* shape, uint64_t w) {
    static const char* names[5] = {"w4", "w8", "w16", "s2", "s4"};
    Tensor t = *shape;
    for (int i = 0; i < e->swar_n; i++)
        if (e->swar_tab[i].rows == t.rows && e->swar_tab[i].cols == t.cols && e->swar_tab[i].fmt == t.fmt) return;
    if (e->swar_n >= (int)(sizeof(e->swar_tab) / sizeof(e->swar_tab[0])) || !e->pf_h || !e->pf_a) return;
    if ((uint64_t)4 * t.cols > pf_h_floats) return;
    if (t.fmt == 8 && !e->k_swar3[0][0]) return;
    int ent = e->swar_n++;
    e->swar_tab[ent].rows = t.rows, e->swar_tab[ent].cols = t.cols, e->swar_tab[ent].fmt = t.fmt;
    while ((uint64_t)4 * t.rows > pf_a_floats) t.rows = (t.rows / 2) & ~127u;   // (timed on the first rows)
    float* X = (float*)(uintptr_t)e->pf_h;
    for (uint32_t k = 0; k < 4 * t.cols; k++) X[k] = (float)(((k * 2654435761u) >> 16) & 1023) / 512.0f - 1.0f;
    
    char line[400];
    int o = snprintf(line, sizeof(line), "swar tune %s %ux%u%s:", t.fmt == 8 ? "q3s" : "q4s", e->swar_tab[ent].rows, t.cols,
                     t.rows != e->swar_tab[ent].rows ? " (timed on the first rows)" : "");
    for (uint32_t n = 1; n <= 4; n++) {
        static float ref[2048];
        volatile float* Y = (volatile float*)(uintptr_t)e->pf_a;
        uint64_t ny = (uint64_t)n * t.rows, stride = ny / 2048 + 1;
        double best = 1e9;
        uint32_t nsamp = 0;
        e->swar_tab[ent].v[n - 1] = 1;
        o += snprintf(line + o, sizeof(line) - o, " n=%u", n);
        for (int v = 0; v < 5; v++) {
            if (swar_split[v] > 1 && t.rows > e->yp_rows) continue;
            if (!(t.fmt == 8 ? e->k_swar3 : e->k_swar)[n - 1][v]) continue;   // (q3s has no 16-wave variant)
            begin(e);
            dispatch(e, e->k_qx8, args(e, "ppppup", e->pf_h, e->x8, e->dx8, e->init8, n * (t.cols / 32), (uint64_t)0),
                     (n * (t.cols / 32) + 255) / 256, 256);
            for (int rep = 0; rep < 3; rep++) swar_chunk(e, &t, w, v, n, 0, e->pf_a, 0, 0);
            if (submit(e)) return;
            double dt = e->last_gpu_seconds / 3;
            float md = 0, mx = 0;
            uint32_t c = 0;
            for (uint64_t y = 0; y < ny && c < 2048; y += stride, c++) {
                float a = Y[y];
                if (v == 0) ref[c] = a;
                float d = a - ref[c] < 0 ? ref[c] - a : a - ref[c], r = ref[c] < 0 ? -ref[c] : ref[c];
                if (!(d <= md)) md = d;
                if (r > mx) mx = r;
            }
            if (v == 0) nsamp = c;
            int ok = c == nsamp && md <= 1e-4f * (mx > 0 ? mx : 1);
            o += snprintf(line + o, sizeof(line) - o, " %s %.3f%s", names[v], dt * 1e3, ok ? "" : " WRONG");
            if (ok && dt < best) best = dt, e->swar_tab[ent].v[n - 1] = (uint8_t)v;
        }
        o += snprintf(line + o, sizeof(line) - o, " -> %s;", names[e->swar_tab[ent].v[n - 1]]);
    }
    say("%s", line);
}

static void swar_tune(Engine* e) {
    // Q4S/Q3S currently need a portable replacement for Garlic's AMD v_mad_i32_i24
    // operation. The Android build intentionally rejects those weight layouts for now.
    (void)e;
}



// ---- speech to text: Parakeet TDT 0.6B v3 -----------------------------------------------------------------------
// Mel features on the CPU, encoder on the GPU in one submission, then the TDT greedy loop, one GPU step per decision
// (prediction network when a token was emitted, joint, argmax).

static const AsrTensor* at(Engine* e, const char* name) {
    for (uint32_t i = 0; i < e->asr.nt; i++)
        if (!strcmp(e->asr.t[i].name, name)) return &e->asr.t[i];
    return NULL;
}


int engine_flex_test(Engine* e) {
    (void)e;
    return -1;
}

void engine_close(Engine* e) {
    if (!e) return;
    if (e->android_cl_runtime) {
        matrix_cl_destroy((MatrixClRuntime*)e->android_cl_runtime);
        e->android_cl_runtime = NULL;
        e->android_cl_memory = NULL;
    }
    if (e->fd >= 0) close(e->fd);
    free(e->ids);
    free(e->row_cache);
    free(e->row_tag);
    free(e->layer_base);
    free(e->t);
    free(e->kc);
    free(e->vc);
    free(e->mem);
    e->mem = NULL;
    e->mem_size = e->next = 0;
}

