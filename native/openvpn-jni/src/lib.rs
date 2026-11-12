use std::sync::{Arc, Mutex, OnceLock};

use jni::{
    objects::{GlobalRef, JObject, JString, JValue},
    sys::{jboolean, jint, jlong},
    JNIEnv, JavaVM,
};
use openvpn_connect::{Client, Config, Credentials, Event, EventHandler, TunBuilder, DnsOptions};

#[derive(Clone)]
struct AndroidBridge {
    vm: Arc<JavaVM>,
    service: GlobalRef,
}

impl AndroidBridge {
    fn with_env<R>(&self, f: impl FnOnce(&mut JNIEnv<'_>) -> jni::errors::Result<R>) -> Option<R> {
        let mut env = self.vm.attach_current_thread().ok()?;
        f(&mut env).ok()
    }

    fn bool0(&self, method: &str) -> bool {
        self.with_env(|env| {
            env.call_method(self.service.as_obj(), method, "()Z", &[])?.z()
        }).unwrap_or(false)
    }

    fn bool_i(&self, method: &str, value: i32) -> bool {
        self.with_env(|env| {
            env.call_method(self.service.as_obj(), method, "(I)Z", &[JValue::Int(value)])?.z()
        }).unwrap_or(false)
    }

    fn bool_string(&self, method: &str, value: &str) -> bool {
        self.with_env(|env| {
            let value = env.new_string(value)?;
            env.call_method(
                self.service.as_obj(),
                method,
                "(Ljava/lang/String;)Z",
                &[JValue::Object(value.as_ref())],
            )?.z()
        }).unwrap_or(false)
    }

    fn bool_string_int(&self, method: &str, value: &str, number: i32) -> bool {
        self.with_env(|env| {
            let value = env.new_string(value)?;
            env.call_method(
                self.service.as_obj(),
                method,
                "(Ljava/lang/String;I)Z",
                &[JValue::Object(value.as_ref()), JValue::Int(number)],
            )?.z()
        }).unwrap_or(false)
    }

    fn bool_address(&self, address: &str, prefix: i32, gateway: &str, ipv6: bool, net30: bool) -> bool {
        self.with_env(|env| {
            let address = env.new_string(address)?;
            let gateway = env.new_string(gateway)?;
            env.call_method(
                self.service.as_obj(),
                "nativeAddAddress",
                "(Ljava/lang/String;ILjava/lang/String;ZZ)Z",
                &[
                    JValue::Object(address.as_ref()),
                    JValue::Int(prefix),
                    JValue::Object(gateway.as_ref()),
                    JValue::Bool(ipv6 as u8),
                    JValue::Bool(net30 as u8),
                ],
            )?.z()
        }).unwrap_or(false)
    }

    fn bool_route_flags(&self, ipv4: bool, ipv6: bool, flags: u32) -> bool {
        self.with_env(|env| {
            env.call_method(
                self.service.as_obj(),
                "nativeRerouteGateway",
                "(ZZI)Z",
                &[
                    JValue::Bool(ipv4 as u8),
                    JValue::Bool(ipv6 as u8),
                    JValue::Int(flags as i32),
                ],
            )?.z()
        }).unwrap_or(false)
    }

    fn bool_route(&self, method: &str, address: &str, prefix: i32, metric: i32, ipv6: bool) -> bool {
        self.with_env(|env| {
            let address = env.new_string(address)?;
            env.call_method(
                self.service.as_obj(),
                method,
                "(Ljava/lang/String;IIZ)Z",
                &[
                    JValue::Object(address.as_ref()),
                    JValue::Int(prefix),
                    JValue::Int(metric),
                    JValue::Bool(ipv6 as u8),
                ],
            )?.z()
        }).unwrap_or(false)
    }

    fn notify(&self, name: &str, info: &str, error: bool) {
        let _ = self.with_env(|env| {
            let name = env.new_string(name)?;
            let info = env.new_string(info)?;
            env.call_method(
                self.service.as_obj(),
                "nativeOnOpenVpnEvent",
                "(Ljava/lang/String;Ljava/lang/String;Z)V",
                &[
                    JValue::Object(name.as_ref()),
                    JValue::Object(info.as_ref()),
                    JValue::Bool(error as u8),
                ],
            )?;
            Ok(())
        });
    }
}

struct MatrixTunBuilder {
    bridge: AndroidBridge,
}

impl TunBuilder for MatrixTunBuilder {
    fn new_tunnel(&self) -> bool {
        self.bridge.bool0("nativeNewTunnel")
    }

    fn set_layer(&self, layer: i32) -> bool {
        layer == 3 && self.bridge.bool_i("nativeSetLayer", layer)
    }

    fn set_remote_address(&self, _address: &str, _ipv6: bool) -> bool {
        // The transport socket is protected separately through VpnService.protect().
        true
    }

    fn add_address(&self, address: &str, prefix_length: i32, gateway: &str, ipv6: bool, net30: bool) -> bool {
        self.bridge.bool_address(address, prefix_length, gateway, ipv6, net30)
    }

    fn set_route_metric_default(&self, _metric: i32) -> bool {
        true
    }

    fn reroute_gateway(&self, ipv4: bool, ipv6: bool, flags: u32) -> bool {
        self.bridge.bool_route_flags(ipv4, ipv6, flags)
    }

    fn add_route(&self, address: &str, prefix_length: i32, metric: i32, ipv6: bool) -> bool {
        self.bridge.bool_route("nativeAddRoute", address, prefix_length, metric, ipv6)
    }

    fn exclude_route(&self, address: &str, prefix_length: i32, metric: i32, ipv6: bool) -> bool {
        self.bridge.bool_route("nativeExcludeRoute", address, prefix_length, metric, ipv6)
    }

    fn set_dns_options(&self, dns: &DnsOptions) -> bool {
        let addresses = dns.servers.iter()
            .flat_map(|server| server.addresses.iter().map(|address| address.address.clone()))
            .filter(|address| !address.is_empty())
            .collect::<Vec<_>>()
            .join(";");
        self.bridge.bool_string("nativeSetDnsServers", &addresses)
    }

    fn set_mtu(&self, mtu: i32) -> bool {
        self.bridge.bool_i("nativeSetMtu", mtu)
    }

    fn set_session_name(&self, name: &str) -> bool {
        self.bridge.bool_string("nativeSetSessionName", name)
    }

    fn set_allow_family(&self, _address_family: i32, allow: bool) -> bool {
        allow
    }

    fn set_allow_local_dns(&self, _allow: bool) -> bool {
        true
    }

    fn establish(&self) -> i32 {
        self.bridge.with_env(|env| {
            env.call_method(self.bridge.service.as_obj(), "nativeEstablish", "()I", &[])?.i()
        }).unwrap_or(-1)
    }

    fn persist(&self) -> bool {
        true
    }

    fn teardown(&self, disconnect: bool) {
        let _ = self.bridge.with_env(|env| {
            env.call_method(
                self.bridge.service.as_obj(),
                "nativeTeardown",
                "(Z)V",
                &[JValue::Bool(disconnect as u8)],
            )?;
            Ok(())
        });
    }
}

struct MatrixEventHandler {
    bridge: AndroidBridge,
    tun: MatrixTunBuilder,
}

impl EventHandler for MatrixEventHandler {
    fn event(&self, event: Event) {
        self.bridge.notify(&event.name, &event.info, event.error || event.fatal);
    }

    fn log(&self, text: &str) {
        // Forward only concise lifecycle events to Android; do not persist TLS/key material.
        if text.contains("ERROR") || text.contains("WARNING") {
            self.bridge.notify("OPENVPN_LOG", text, text.contains("ERROR"));
        }
    }

    fn socket_protect(&self, socket: isize, _remote: &str, _ipv6: bool) -> bool {
        self.bridge.bool_i("nativeProtectSocket", socket as i32)
    }

    fn tun_builder(&self) -> Option<&dyn TunBuilder> {
        Some(&self.tun)
    }
}

static CLIENT: OnceLock<Mutex<Option<Client>>> = OnceLock::new();

fn client_slot() -> &'static Mutex<Option<Client>> {
    CLIENT.get_or_init(|| Mutex::new(None))
}

fn throw(env: &mut JNIEnv<'_>, message: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
}

#[no_mangle]
pub extern "system" fn Java_org_matrixvpn_vpn_MatrixVpnService_nativeStart(
    mut env: JNIEnv,
    service: JObject,
    profile: JString,
    username: JString,
    password: JString,
) -> jboolean {
    let result = (|| -> Result<(), String> {
        let profile: String = env.get_string(&profile).map_err(|e| e.to_string())?.into();
        let username: String = env.get_string(&username).map_err(|e| e.to_string())?.into();
        let password: String = env.get_string(&password).map_err(|e| e.to_string())?.into();
        if profile.trim().is_empty() {
            return Err("Профиль OpenVPN пуст.".into());
        }
        {
            let active = client_slot().lock().map_err(|_| "Состояние OpenVPN заблокировано.".to_string())?;
            if active.is_some() {
                return Err("В MATRIX уже выполняется сеанс OpenVPN.".into());
            }
        }

        let bridge = AndroidBridge {
            vm: Arc::new(env.get_java_vm().map_err(|e| e.to_string())?),
            service: env.new_global_ref(service).map_err(|e| e.to_string())?,
        };
        let handler = MatrixEventHandler {
            bridge: bridge.clone(),
            tun: MatrixTunBuilder { bridge: bridge.clone() },
        };
        let client = Client::new(handler).map_err(|e| format!("OpenVPN 3: {e}"))?;
        let evaluation = client.evaluate(&Config::new(profile))
            .map_err(|e| format!("Профиль OpenVPN отклонён: {e}"))?;

        if !evaluation.autologin {
            if username.trim().is_empty() || password.is_empty() {
                return Err("Этот OpenVPN-профиль требует имя пользователя и пароль.".into());
            }
            client.provide_credentials(&Credentials::new(username, password))
                .map_err(|e| format!("Не удалось передать учётные данные: {e}"))?;
        }

        let runner = client.clone();
        {
            let mut active = client_slot().lock().map_err(|_| "Состояние OpenVPN заблокировано.".to_string())?;
            if active.is_some() {
                return Err("Другой сеанс OpenVPN уже запущен.".into());
            }
            *active = Some(client);
        }
        std::thread::Builder::new()
            .name("MATRIX-OpenVPN3".into())
            .spawn(move || {
                let result = runner.connect();
                match result {
                    Ok(status) => bridge.notify("SESSION_FINISHED", &format!("{}: {}", status.status, status.message), false),
                    Err(error) => bridge.notify("SESSION_FINISHED", &format!("Ошибка OpenVPN 3: {error}"), true),
                }
                if let Ok(mut active) = client_slot().lock() {
                    *active = None;
                }
            })
            .map_err(|e| {
                if let Ok(mut active) = client_slot().lock() { *active = None; }
                format!("Не удалось запустить поток OpenVPN: {e}")
            })?;
        Ok(())
    })();

    match result {
        Ok(()) => 1,
        Err(message) => {
            throw(&mut env, &message);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_org_matrixvpn_vpn_MatrixVpnService_nativeStop(
    _env: JNIEnv,
    _service: JObject,
    
) {
    let client = client_slot().lock().ok().and_then(|active| active.as_ref().cloned());
    if let Some(client) = client {
        client.stop();
    }
}
