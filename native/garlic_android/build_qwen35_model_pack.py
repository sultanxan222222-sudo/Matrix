#!/usr/bin/env python3
"""Create a Garlic-compatible Qwen3.5-0.8B Q4i model pack for MATRIX Android.

This does not convert the model to another LLM runtime. It uses Garlic's exact Q4
quantizer, tensor order, Qwen3.5 PMQ5 model-index writer and tokenizer exporter.
The output weights are then interleaved as Garlic Q4i so the retained Garlic GPU
kernels can consume them. The source model remains the official Qwen checkpoint.
"""

from __future__ import annotations

import json
import os
import shutil
import sys
from pathlib import Path

import numpy as np
from huggingface_hub import snapshot_download
from safetensors import safe_open

ROOT = Path(__file__).resolve().parents[2]
GARLIC = ROOT / "third_party" / "garlic-llm"
GARLIC_PC = GARLIC / "pc"
MODEL_REPO = os.environ.get("MATRIX_QWEN_MODEL", "Qwen/Qwen3.5-0.8B")
MODEL_NAME = "qwen3.5-0.8b-q4i"
OUT_DIR = Path(os.environ.get("MATRIX_MODEL_OUTPUT", ROOT / "build" / "matrix-qwen35-0.8b"))
TMP_DIR = Path(os.environ.get("MATRIX_MODEL_WORK", ROOT / "build" / "qwen35-0.8b-work"))
sys.path.insert(0, str(GARLIC_PC))

from q4 import quantize_q4, interleave_q4  # noqa: E402


def put_aligned(out, index, offset, key, payload, **meta):
    pad = (-offset) % 256
    if pad:
        out.write(bytes(pad))
        offset += pad
    out.write(payload)
    index[key] = dict(offset=offset, size=len(payload), **meta)
    return offset + len(payload)


def main() -> None:
    import export_chat

    TMP_DIR.mkdir(parents=True, exist_ok=True)
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    local_model = Path(snapshot_download(
        repo_id=MODEL_REPO,
        allow_patterns=[
            "config.json",
            "model.safetensors.index.json",
            "*.safetensors",
            "tokenizer.json",
            "tokenizer_config.json",
            "merges.txt",
            "vocab.json",
            "LICENSE",
            "README.md",
        ],
    ))
    config = json.loads((local_model / "config.json").read_text(encoding="utf-8"))
    cfg = config.get("text_config", config)
    weight_map = json.loads((local_model / "model.safetensors.index.json").read_text(encoding="utf-8"))["weight_map"]

    handles = {}
    def get(name: str) -> np.ndarray:
        shard = weight_map.get(name)
        if not shard:
            raise KeyError(f"Required Qwen tensor is missing: {name}")
        if shard not in handles:
            handles[shard] = safe_open(str(local_model / shard), framework="pt", device="cpu")
        return handles[shard].get_tensor(name).float().numpy()

    source_bin = TMP_DIR / f"{MODEL_NAME}-source-q4.bin"
    source_meta = TMP_DIR / f"{MODEL_NAME}-source-q4.json"
    index = {}
    offset = 0

    def put_f32(out, key: str, values: np.ndarray) -> None:
        nonlocal offset
        data = np.ascontiguousarray(values, dtype=np.float32).tobytes()
        offset = put_aligned(out, index, offset, key, data, n=int(values.size))

    def put_q4(out, key: str, matrix: np.ndarray, fmt: str = "q4") -> None:
        nonlocal offset
        matrix = np.ascontiguousarray(matrix, dtype=np.float32)
        if matrix.ndim != 2 or matrix.shape[1] % 32:
            raise ValueError(f"{key}: Q4 matrix shape must be [rows, cols divisible by 32], got {matrix.shape}")
        # Garlic Q4i stores rows in 64-row tiles. Pad output rows with exact zero weights;
        # Qwen's layer dimensions still govern which projected channels the engine consumes.
        # Q4i matrices are tiled in 64 rows. The embedding is deliberately kept in
        # Garlic's original row-major q4row layout: the CPU fetches just the requested
        # row and uploads that activation, avoiding a single 140+ MiB mobile GPU buffer.
        if fmt == "q4i" and matrix.shape[0] % 64:
            padded_rows = ((matrix.shape[0] + 63) // 64) * 64
            padded = np.zeros((padded_rows, matrix.shape[1]), dtype=np.float32)
            padded[:matrix.shape[0], :] = matrix
            matrix = padded
        packed, scales = quantize_q4(matrix)
        payload = packed.tobytes() + scales.tobytes()
        offset = put_aligned(
            out, index, offset, key, payload,
            rows=int(matrix.shape[0]), cols=int(matrix.shape[1]),
            scales=int(packed.nbytes), fmt=fmt
        )

    prefix = "model.language_model."
    hidden = int(cfg["hidden_size"])
    num_heads = int(cfg["num_attention_heads"])
    head_dim = int(cfg["head_dim"])
    if cfg.get("model_type") != "qwen3_5_text":
        raise ValueError(f"Unsupported source architecture: {cfg.get('model_type')}")
    if int(cfg["num_hidden_layers"]) != len(cfg["layer_types"]):
        raise ValueError("Qwen config layer_types does not match num_hidden_layers.")

    print(f"Converting {MODEL_REPO}: {cfg['num_hidden_layers']} layers, hidden={hidden}, vocab={cfg['vocab_size']}")
    with source_bin.open("wb") as out:
        put_q4(out, "embed", get(prefix + "embed_tokens.weight"), fmt="q4row")
        put_f32(out, "final_norm", 1.0 + get(prefix + "norm.weight"))

        for layer, kind in enumerate(cfg["layer_types"]):
            base = f"{prefix}layers.{layer}."
            put_f32(out, f"{layer}.attn_norm", 1.0 + get(base + "input_layernorm.weight"))
            put_f32(out, f"{layer}.mlp_norm", 1.0 + get(base + "post_attention_layernorm.weight"))

            if kind == "full_attention":
                attention = base + "self_attn."
                put_f32(out, f"{layer}.q_norm", 1.0 + get(attention + "q_norm.weight"))
                put_f32(out, f"{layer}.k_norm", 1.0 + get(attention + "k_norm.weight"))
                qg = get(attention + "q_proj.weight").reshape(num_heads, 2, head_dim, -1)
                q = qg[:, 0].reshape(num_heads * head_dim, hidden)
                gate = qg[:, 1].reshape(num_heads * head_dim, hidden)
                kv = np.concatenate([
                    q,
                    get(attention + "k_proj.weight"),
                    get(attention + "v_proj.weight"),
                    gate,
                ])
                put_q4(out, f"{layer}.wqkv", kv)
                put_q4(out, f"{layer}.wo", get(attention + "o_proj.weight"))
                del qg, q, gate, kv
            elif kind == "linear_attention":
                linear = base + "linear_attn."
                w_in = np.concatenate([
                    get(linear + "in_proj_qkv.weight"),
                    get(linear + "in_proj_z.weight"),
                    get(linear + "in_proj_b.weight"),
                    get(linear + "in_proj_a.weight"),
                ])
                put_q4(out, f"{layer}.w_in", w_in)
                put_f32(out, f"{layer}.conv",
                        get(linear + "conv1d.weight").reshape(-1, int(cfg["linear_conv_kernel_dim"])))
                put_f32(out, f"{layer}.A", -np.exp(get(linear + "A_log")))
                put_f32(out, f"{layer}.dt_bias", get(linear + "dt_bias"))
                put_f32(out, f"{layer}.gnorm", get(linear + "norm.weight"))
                put_q4(out, f"{layer}.wout", get(linear + "out_proj.weight"))
                del w_in
            else:
                raise ValueError(f"Unknown Qwen3.5 layer type at {layer}: {kind}")

            put_q4(out, f"{layer}.wgu", np.concatenate([
                get(base + "mlp.gate_proj.weight"),
                get(base + "mlp.up_proj.weight"),
            ]))
            put_q4(out, f"{layer}.wdown", get(base + "mlp.down_proj.weight"))
            print(f"  layer {layer + 1}/{len(cfg['layer_types'])}: {kind}, {offset / (1024 * 1024):.1f} MiB", flush=True)

    metadata = {"config": cfg, "size": offset, "tensors": index}
    source_meta.write_text(json.dumps(metadata, indent=1), encoding="utf-8")

    # Convert Garlic's row-major Q4 matrices into its exact 64-row Q4i tile layout.
    q4i_bin = OUT_DIR / f"{MODEL_NAME}.bin"
    q4i_meta = dict(metadata)
    position = 0
    with source_bin.open("rb") as src, q4i_bin.open("wb") as dst:
        for key, tensor in sorted(q4i_meta["tensors"].items(), key=lambda item: item[1]["offset"]):
            if tensor["offset"] > position:
                dst.write(bytes(tensor["offset"] - position))
            src.seek(tensor["offset"])
            payload = src.read(tensor["size"])
            if len(payload) != tensor["size"]:
                raise IOError(f"Truncated source tensor: {key}")
            if tensor.get("fmt") == "q4":
                rows, cols = int(tensor["rows"]), int(tensor["cols"])
                if rows % 64:
                    raise ValueError(f"{key}: Garlic Q4i requires rows divisible by 64, got {rows}")
                packed_size = int(tensor["scales"])
                packed = np.frombuffer(payload[:packed_size], dtype=np.uint8).reshape(rows, cols // 2)
                scales = np.frombuffer(payload[packed_size:], dtype=np.float16).reshape(rows, cols // 32)
                nibble_bytes, scale_bytes = interleave_q4(packed, scales)
                payload = nibble_bytes + scale_bytes
                tensor["fmt"] = "q4i"
            elif tensor.get("fmt") == "q4row":
                # Keep Q4 row-major bytes for Garlic's CPU-embedding path.
                pass
            dst.write(payload)
            position = int(tensor["offset"]) + int(tensor["size"])
    q4i_meta["size"] = q4i_bin.stat().st_size
    (OUT_DIR / f"{MODEL_NAME}.json").write_text(json.dumps(q4i_meta, indent=1), encoding="utf-8")

    # Reuse Garlic's authoritative PMQ5 index writer and tokenizer exporter.
    export_chat.OUT = OUT_DIR
    export_chat.export_model_index_qwen35(MODEL_NAME, q4i_meta)
    # Keep the same 2K limit compiled into Android's Qwen3.5 kernels. This is enough
    # for the compact network-policy prompt and sharply reduces the mobile KV cache.
    index_path = OUT_DIR / "model.idx"
    index_bytes = bytearray(index_path.read_bytes())
    import struct
    struct.pack_into("<I", index_bytes, 4 + 7 * 4, 2048)
    index_path.write_bytes(index_bytes)
    export_chat.export_tokenizer(local_model)

    shutil.copy2(local_model / "LICENSE", OUT_DIR / "QWEN-LICENSE")
    (OUT_DIR / "MODEL-INFO.txt").write_text(
        "MATRIX Garlic Qwen3.5-0.8B Q4i model pack\n"
        f"Source model: {MODEL_REPO}\n"
        "Quantization and tensor layout: upstream Garlic LLM Q4 / Q4i format\n"
        "Engine: Android port of Garlic Qwen3.5 using clspv + clvk + Vulkan\n"
        "Weights licence: Apache-2.0 (Qwen model licence supplied as QWEN-LICENSE)\n"
        "Files: model.idx, tokenizer.bin, " + f"{MODEL_NAME}.bin\n"
        "Install all files into the same private model directory in MATRIX.\n",
        encoding="utf-8",
    )

    import hashlib
    checks = {}
    for file in sorted(OUT_DIR.iterdir()):
        if file.is_file():
            digest = hashlib.sha256(file.read_bytes()).hexdigest()
            checks[file.name] = {"size": file.stat().st_size, "sha256": digest}
    (OUT_DIR / "SHA256SUMS.json").write_text(json.dumps(checks, indent=2), encoding="utf-8")

    print(f"Model pack created: {OUT_DIR}")
    print(f"Q4i weights: {q4i_bin.stat().st_size / (1024 * 1024):.1f} MiB")


if __name__ == "__main__":
    main()
