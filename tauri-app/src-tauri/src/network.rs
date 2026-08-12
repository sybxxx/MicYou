use log;
use mdns_sd::{IfKind, ServiceDaemon, ServiceInfo};
use micyou_protocol::MDNS_SERVICE_TYPE;
use std::collections::HashMap;
use std::net::IpAddr;

pub struct NetworkManager {
    mdns: ServiceDaemon,
    service_fullname: String,
}

impl NetworkManager {
    pub fn start_mdns(port: u16, bind_address: &str) -> Result<Self, Box<dyn std::error::Error>> {
        Self::start_service(MDNS_SERVICE_TYPE, "MicYou", port, bind_address)
    }

    pub fn stop_mdns(&self) {
        let _ = self.mdns.unregister(&self.service_fullname);
        let _ = self.mdns.shutdown();
    }

    pub fn start_web_mdns(
        port: u16,
        bind_address: &str,
    ) -> Result<Self, Box<dyn std::error::Error>> {
        Self::start_service(
            micyou_protocol::MDNS_WEB_SERVICE_TYPE,
            "MicYou Web",
            port,
            bind_address,
        )
    }

    fn start_service(
        service_type: &str,
        display_name: &str,
        port: u16,
        bind_address: &str,
    ) -> Result<Self, Box<dyn std::error::Error>> {
        let mdns = ServiceDaemon::new()?;
        let host_name = hostname::get()?
            .into_string()
            .unwrap_or_else(|_| "UnknownHost".to_string());
        let instance_name = format!("{} ({})", display_name, host_name);
        let local_ip = if bind_address == "0.0.0.0" {
            Self::get_best_ip().unwrap_or_else(|| IpAddr::from([127, 0, 0, 1]))
        } else {
            bind_address.parse()?
        };
        let service_fullname = format!("{}.{}", instance_name, service_type);
        let valid_host_name = format!("{}.local.", host_name.replace(" ", "-"));
        let properties: HashMap<String, String> = HashMap::new();
        let mut service_info = ServiceInfo::new(
            service_type,
            &instance_name,
            &valid_host_name,
            local_ip,
            port,
            Some(properties),
        )?;

        // Restrict both announcements and query responses to the selected adapter.
        // Advertising on every interface lets Android discover a valid MicYou service
        // whose address belongs to VMware/Hyper-V and cannot be reached from the phone.
        mdns.disable_interface(IfKind::All)?;
        mdns.enable_interface(local_ip)?;
        service_info.set_interfaces(vec![IfKind::Addr(local_ip)]);
        mdns.register(service_info)?;

        log::info!(
            "mDNS service registered: {} at {}:{}",
            service_fullname,
            local_ip,
            port
        );
        Ok(Self {
            mdns,
            service_fullname,
        })
    }

    fn get_best_ip() -> Option<IpAddr> {
        let default_route_ip = local_ip_address::local_ip()
            .ok()
            .filter(Self::is_usable_ipv4);

        if let Ok(interfaces) = local_ip_address::list_afinet_netifas() {
            return Self::select_best_ip(default_route_ip, &interfaces);
        }

        default_route_ip
    }

    fn select_best_ip(
        default_route_ip: Option<IpAddr>,
        interfaces: &[(String, IpAddr)],
    ) -> Option<IpAddr> {
        if let Some(ip) = default_route_ip.filter(|candidate| {
            interfaces
                .iter()
                .any(|(name, address)| address == candidate && !Self::is_virtual_interface(name))
        }) {
            return Some(ip);
        }

        interfaces
            .iter()
            .filter(|(name, ip)| Self::is_usable_ipv4(ip) && !Self::is_virtual_interface(name))
            .max_by_key(|(_, ip)| Self::score_ip(*ip))
            .map(|(_, ip)| *ip)
    }

    fn is_usable_ipv4(ip: &IpAddr) -> bool {
        match ip {
            IpAddr::V4(ip) => !ip.is_loopback() && !ip.is_link_local() && !ip.is_unspecified(),
            IpAddr::V6(_) => false,
        }
    }

    fn is_virtual_interface(name: &str) -> bool {
        const VIRTUAL_KEYWORDS: &[&str] = &[
            "vmware",
            "virtualbox",
            "hyper-v",
            "vethernet",
            "vswitch",
            "wsl",
            "docker",
            "veth",
            "tunnel",
            "teredo",
            "isatap",
            "vpn",
            "tailscale",
            "clash",
            "flclash",
        ];

        let name = name.to_lowercase();
        VIRTUAL_KEYWORDS
            .iter()
            .any(|keyword| name.contains(keyword))
    }

    fn score_ip(ip: IpAddr) -> i32 {
        match ip {
            IpAddr::V4(ip) if ip.is_private() => {
                let octets = ip.octets();
                if octets[0] == 192 && octets[1] == 168 {
                    100
                } else if octets[0] == 172 {
                    80
                } else {
                    60
                }
            }
            IpAddr::V4(_) => 10,
            IpAddr::V6(_) => 0,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::NetworkManager;
    use std::net::IpAddr;

    #[test]
    fn recognizes_common_virtual_adapter_names() {
        assert!(NetworkManager::is_virtual_interface(
            "VMware Network Adapter VMnet8"
        ));
        assert!(NetworkManager::is_virtual_interface(
            "vEthernet (Default Switch)"
        ));
        assert!(!NetworkManager::is_virtual_interface("Ethernet"));
        assert!(!NetworkManager::is_virtual_interface("WLAN"));
    }

    #[test]
    fn rejects_loopback_link_local_and_ipv6_addresses() {
        for address in ["127.0.0.1", "169.254.1.15", "::1"] {
            let ip: IpAddr = address.parse().unwrap();
            assert!(!NetworkManager::is_usable_ipv4(&ip));
        }
        assert!(NetworkManager::is_usable_ipv4(
            &"192.168.2.2".parse().unwrap()
        ));
    }

    #[test]
    fn prefers_private_lan_addresses() {
        assert!(
            NetworkManager::score_ip("192.168.2.2".parse().unwrap())
                > NetworkManager::score_ip("10.0.0.2".parse().unwrap())
        );
        assert!(
            NetworkManager::score_ip("10.0.0.2".parse().unwrap())
                > NetworkManager::score_ip("8.8.8.8".parse().unwrap())
        );
    }

    #[test]
    fn prefers_default_route_over_virtual_and_other_private_interfaces() {
        let interfaces = vec![
            (
                "VMware Network Adapter VMnet8".to_string(),
                "192.168.249.1".parse().unwrap(),
            ),
            ("Ethernet".to_string(), "192.168.2.2".parse().unwrap()),
            (
                "vEthernet (Default Switch)".to_string(),
                "172.31.240.1".parse().unwrap(),
            ),
        ];

        assert_eq!(
            NetworkManager::select_best_ip(Some("192.168.2.2".parse().unwrap()), &interfaces),
            Some("192.168.2.2".parse().unwrap())
        );
    }

    #[test]
    fn falls_back_to_best_non_virtual_interface() {
        let interfaces = vec![
            (
                "VMware Network Adapter VMnet1".to_string(),
                "192.168.40.1".parse().unwrap(),
            ),
            ("Ethernet".to_string(), "192.168.2.2".parse().unwrap()),
        ];

        assert_eq!(
            NetworkManager::select_best_ip(None, &interfaces),
            Some("192.168.2.2".parse().unwrap())
        );
    }
}
