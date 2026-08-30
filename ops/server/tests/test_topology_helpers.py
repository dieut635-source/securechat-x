from __future__ import annotations

import builtins
import importlib.util
import io
import json
import sys
import unittest
from copy import deepcopy
from pathlib import Path
from unittest import mock


SCRIPT_DIR = Path(__file__).resolve().parents[1] / "scripts"


def load_script(name: str):
    path = SCRIPT_DIR / name
    module_name = name.replace("-", "_").replace(".py", "")
    spec = importlib.util.spec_from_file_location(module_name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


nginx_audit = load_script("verify-nginx-topology.py")
runtime_audit = load_script("verify-synapse-runtime.py")


def closed_nginx() -> str:
    blocks = ["location ^~ /_synapse/admin/ { return 404; }"]
    for version in ("v3", "r0", "unstable", "api/v1"):
        for endpoint in ("login", "register"):
            path = f"/_matrix/client/{version}/{endpoint}"
            blocks.extend(
                [
                    f"location = {path} {{ return 404; }}",
                    f"location ^~ {path}/ {{ return 404; }}",
                ]
            )
    return """events {}
http {
  server { listen 80; server_name chat.securechat.com.au;
    return 301 https://chat.securechat.com.au$request_uri; }
  server { listen 443 ssl; server_name chat.securechat.com.au;
    %s
    root /var/www/securechat-public;
    location = /.well-known/matrix/client {
      alias /var/www/securechat-public/.well-known/matrix/client;
    }
    location /_matrix { proxy_pass http://synapse:8008; }
    location /livekit/sfu/ { proxy_pass http://172.17.0.1:7880/; }
    location = /livekit/sfu { return 301 https://$host/livekit/sfu/; }
    location /livekit/jwt/ { proxy_pass http://lk-jwt:8080/; }
    location / { try_files $uri $uri/ =404; }
  }
}
""" % "\n".join(blocks)


class NginxTopologyTests(unittest.TestCase):
    def test_reviewed_closed_topology_passes(self) -> None:
        nginx_audit.audit(closed_nginx(), "chat.securechat.com.au", True)

    def test_routing_bypass_shapes_fail_closed(self) -> None:
        base = closed_nginx()
        fixtures = {
            "inline_other_vhost": base
            + "server { listen 444 ssl; server_name other.invalid; "
            "location / { proxy_pass http://synapse:8008; } }",
            "inline_upstream": "upstream matrix_backend { server synapse:8008; }"
            + base,
            "generic_proxy": base.replace(
                "try_files $uri $uri/ =404", "proxy_pass http://synapse:8008"
            ),
            "multiline_proxy": base.replace(
                "location / { try_files $uri $uri/ =404; }",
                "location / { proxy_pass\n http://synapse:8008; }",
            ),
            "specific_auth_proxy": base.replace(
                "location / { try_files",
                "location ^~ /_matrix/client/v3/login/sso/private { "
                "proxy_pass http://synapse:8008; } location / { try_files",
            ),
            "extra_return": base.replace(
                "location = /_matrix/client/v3/login { return 404; }",
                "location = /_matrix/client/v3/login { return 200; return 404; }",
            ),
            "conditional": base.replace(
                "location / { try_files",
                "if ($remote_addr = 127.0.0.1) { return 404; } "
                "location / { try_files",
            ),
            "lua": base.replace(
                "location / { try_files",
                "location /hidden { content_by_lua_block { return 404; } } "
                "location / { try_files",
            ),
            "static_other_vhost": base
            + "server { listen 8448 ssl; server_name other.invalid; "
            "root /srv/rogue; location / { try_files $uri =404; } }",
            "scripted_other_vhost": base
            + "server { listen 9443 ssl; server_name other.invalid; "
            "location / { js_content rogue.handler; } }",
            "second_alias": base.replace(
                "location / { try_files",
                "location /rogue { alias /srv/rogue; } location / { try_files",
            ),
            "internal_fallback": base.replace(
                "try_files $uri $uri/ =404",
                "try_files $uri /_matrix/client/v3/login",
            ),
        }
        for name, fixture in fixtures.items():
            with self.subTest(name=name), self.assertRaises(nginx_audit.TopologyError):
                nginx_audit.audit(fixture, "chat.securechat.com.au", True)

    def test_exact_login_proxy_is_only_allowed_before_closed_phase(self) -> None:
        value = closed_nginx().replace(
            "location = /_matrix/client/v3/login { return 404; }",
            "location = /_matrix/client/v3/login { "
            "proxy_pass http://synapse:8008; }",
        ).replace("location ^~ /_matrix/client/v3/login/ { return 404; }", "")
        nginx_audit.audit(value, "chat.securechat.com.au", False)
        with self.assertRaises(nginx_audit.TopologyError):
            nginx_audit.audit(value, "chat.securechat.com.au", True)


def runtime_documents() -> list[dict[str, object]]:
    labels = {
        "com.docker.compose.project": "matrix",
        "com.docker.compose.project.working_dir": "/opt/matrix",
        "com.docker.compose.project.config_files": (
            "/opt/matrix/docker-compose.yml"
        ),
        "com.docker.compose.container-number": "1",
        "com.docker.compose.oneoff": "False",
    }
    return [
        {
            "Id": "syn-id-123456789",
            "Name": "/synapse",
            "Config": {
                "Image": "matrixdotorg/synapse:v1.159.0",
                "Labels": {
                    **labels,
                    "com.docker.compose.service": "synapse",
                },
            },
            "Path": "python",
            "Args": ["-m", "synapse.app.homeserver"],
            "NetworkSettings": {
                "Ports": {
                    "8008/tcp": [
                        {"HostIp": "127.0.0.1", "HostPort": "8008"}
                    ]
                },
                "Networks": {
                    "matrix_origin": {"Aliases": ["synapse", "matrix-synapse"]}
                }
            },
            "Mounts": [
                {
                    "Type": "bind",
                    "Source": "/opt/matrix/synapse",
                    "Destination": "/data",
                }
            ],
        },
        {
            "Id": "ng-id",
            "Name": "/nginx",
            "Config": {
                "Image": "nginx:stable",
                "Labels": {**labels, "com.docker.compose.service": "nginx"},
            },
            "Path": "nginx",
            "Args": [],
            "NetworkSettings": {
                "Ports": {
                    "80/tcp": [{"HostIp": "0.0.0.0", "HostPort": "80"}],
                    "443/tcp": [{"HostIp": "0.0.0.0", "HostPort": "443"}],
                },
                "Networks": {"matrix_origin": {"Aliases": ["nginx"]}},
            },
            "Mounts": [
                {
                    "Type": "bind",
                    "Source": "/opt/matrix/nginx/nginx.conf",
                    "Destination": "/etc/nginx/nginx.conf",
                }
            ],
        },
    ]


class SynapseRuntimeTests(unittest.TestCase):
    ARGV = [
        "audit",
        "--matrix-dir",
        "/opt/matrix",
        "--compose-file",
        "/opt/matrix/docker-compose.yml",
        "--synapse-container",
        "synapse",
        "--synapse-service",
        "synapse",
        "--nginx-container",
        "nginx",
        "--nginx-service",
        "nginx",
        "--config-path",
        "/data/homeserver.yaml",
        "--nginx-config-path",
        "/etc/nginx/nginx.conf",
    ]

    def execute(
        self,
        documents: list[dict[str, object]],
        *,
        extra_synapse_processes: int = 0,
        origin_internal: bool = True,
        ss_output: str = (
            "LISTEN 0 4096 127.0.0.1:8008 0.0.0.0:* "
            'users:(("docker-proxy",pid=10,fd=4))\n'
        ),
    ) -> int:
        def fake_run(*args: str, input_text=None) -> str:
            del input_text
            if args[:3] == ("docker", "ps", "-q"):
                return "\n".join(str(item["Id"]) for item in documents) + "\n"
            if args[:2] == ("docker", "inspect"):
                return json.dumps(documents)
            if args[:2] == ("docker", "compose"):
                if "config" in args:
                    return json.dumps(
                        {
                            "services": {
                                "synapse": {
                                    "image": "matrixdotorg/synapse:v1.159.0",
                                    "networks": {"origin": None},
                                    "volumes": [
                                        {
                                            "type": "bind",
                                            "source": "/opt/matrix/synapse",
                                            "target": "/data",
                                        }
                                    ],
                                },
                                "nginx": {
                                    "image": "nginx:stable",
                                    "networks": {"origin": None},
                                    "volumes": [
                                        {
                                            "type": "bind",
                                            "source": "/opt/matrix/nginx/nginx.conf",
                                            "target": "/etc/nginx/nginx.conf",
                                        }
                                    ],
                                },
                            },
                            "networks": {
                                "origin": {
                                    "name": "matrix_origin",
                                    "internal": origin_internal,
                                }
                            },
                        }
                    )
                if args[-1] == "synapse":
                    return "syn-id-123456789\n"
                if args[-1] == "nginx":
                    return "ng-id\n"
                raise AssertionError(args)
            if args[:3] == ("docker", "network", "inspect"):
                peers = {
                    str(item["Id"]): {}
                    for item in documents
                    if "matrix_origin"
                    in ((item.get("NetworkSettings") or {}).get("Networks") or {})
                }
                return json.dumps(
                    [
                        {
                            "Name": "matrix_origin",
                            "Internal": origin_internal,
                            "Containers": peers,
                            "Labels": {"com.docker.compose.project": "matrix"},
                        }
                    ]
                )
            if args[:2] == ("docker", "exec"):
                return json.dumps(
                    {
                        "homeserver_processes": 1,
                        "config_paths": ["/data/homeserver.yaml"],
                    }
                )
            if args and args[0] == "ss":
                return ss_output
            raise AssertionError(args)

        def fake_open(path, mode="r", encoding=None, *args, **kwargs):
            del mode, encoding, args, kwargs
            path_value = str(path)
            pid = path_value.split("/")[2]
            if path_value.endswith("/cmdline"):
                if pid == "200":
                    return io.BytesIO(b"nginx:\0master process nginx\0")
                return io.BytesIO(b"python\0-m\0synapse.app.homeserver\0")
            if path_value.endswith("/cgroup"):
                if pid == "200":
                    return io.StringIO("0::/docker/ng-id\n")
                return io.StringIO("0::/docker/syn-id-123456789\n")
            raise AssertionError(path)

        process_ids = ["100", "200"] + [
            str(300 + index) for index in range(extra_synapse_processes)
        ]
        with mock.patch.object(runtime_audit, "run", side_effect=fake_run), \
                mock.patch.object(runtime_audit.os, "listdir", return_value=process_ids), \
                mock.patch.object(builtins, "open", side_effect=fake_open), \
                mock.patch.object(sys, "argv", self.ARGV):
            return runtime_audit.main()

    def test_reviewed_single_runtime_passes(self) -> None:
        self.assertEqual(self.execute(runtime_documents()), 0)

    def test_second_synapse_container_or_process_fails_closed(self) -> None:
        documents = deepcopy(runtime_documents())
        documents.append(
            {
                "Id": "worker-id",
                "Name": "/arbitrary-name",
                "Config": {"Image": "matrixdotorg/synapse:v1.159.0"},
                "Path": "python",
                "Args": ["synapse.app.generic_worker"],
                "NetworkSettings": {"Networks": {}},
            }
        )
        self.assertNotEqual(self.execute(documents), 0)
        self.assertNotEqual(
            self.execute(runtime_documents(), extra_synapse_processes=1),
            0,
        )

    def test_rogue_origin_peer_or_external_network_fails_closed(self) -> None:
        documents = deepcopy(runtime_documents())
        documents.append(
            {
                "Id": "rogue-id",
                "Name": "/caddy",
                "Config": {"Image": "caddy:latest", "Labels": {}},
                "Path": "caddy",
                "Args": [],
                "NetworkSettings": {
                    "Ports": {},
                    "Networks": {"matrix_origin": {"Aliases": ["rogue"]}},
                },
                "Mounts": [],
            }
        )
        self.assertNotEqual(self.execute(documents), 0)
        self.assertNotEqual(
            self.execute(runtime_documents(), origin_internal=False),
            0,
        )

    def test_nginx_identity_or_loopback_listener_mismatch_fails_closed(self) -> None:
        documents = deepcopy(runtime_documents())
        documents[1]["Config"]["Labels"]["com.docker.compose.service"] = "rogue"
        self.assertNotEqual(self.execute(documents), 0)
        self.assertNotEqual(
            self.execute(
                runtime_documents(),
                ss_output=(
                    "LISTEN 0 4096 127.0.0.1:8008 0.0.0.0:* "
                    'users:(("python",pid=10,fd=4))\n'
                ),
            ),
            0,
        )


if __name__ == "__main__":
    unittest.main()
