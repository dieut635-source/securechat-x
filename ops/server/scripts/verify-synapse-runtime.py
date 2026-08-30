#!/usr/bin/env python3
"""Fail-closed identity/topology check for the single Synapse origin."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys


class AuditError(RuntimeError):
    pass


def run(*args: str, input_text: str | None = None) -> str:
    result = subprocess.run(
        args,
        input=input_text,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise AuditError(f"command failed without disclosure: {args[0]} {args[1]}")
    return result.stdout


def _labels(item: dict) -> dict:
    labels = (item.get("Config") or {}).get("Labels") or {}
    if not isinstance(labels, dict):
        raise AuditError("container labels không parse được")
    return labels


def _require_compose_identity(
    item: dict,
    *,
    service_name: str,
    matrix_dir: str,
    compose_file: str,
) -> str:
    labels = _labels(item)
    if labels.get("com.docker.compose.service") != service_name:
        raise AuditError(f"container không thuộc Compose service {service_name}")
    project = labels.get("com.docker.compose.project")
    if not isinstance(project, str) or not project:
        raise AuditError("container thiếu Compose project identity")
    if labels.get("com.docker.compose.container-number") != "1" \
            or labels.get("com.docker.compose.oneoff") != "False":
        raise AuditError("container không phải replica 1 non-oneoff đã review")
    working_dir = labels.get("com.docker.compose.project.working_dir")
    if not isinstance(working_dir, str) \
            or os.path.realpath(working_dir) != os.path.realpath(matrix_dir):
        raise AuditError("Compose working directory không khớp designated project")
    config_files = labels.get("com.docker.compose.project.config_files")
    if not isinstance(config_files, str):
        raise AuditError("container thiếu Compose config-files identity")
    resolved = {
        os.path.realpath(part.strip())
        for part in config_files.split(",")
        if part.strip()
    }
    if resolved != {os.path.realpath(compose_file)}:
        raise AuditError("effective Compose config files không đúng một designated file")
    return project


def _service_networks(service: dict) -> set[str]:
    networks = service.get("networks") or {}
    if isinstance(networks, list):
        if any(not isinstance(value, str) for value in networks):
            raise AuditError("Compose service network list không parse được")
        return set(networks)
    if isinstance(networks, dict) and all(
        isinstance(name, str) for name in networks
    ):
        return set(networks)
    raise AuditError("Compose service networks không parse được")


def _covering_compose_mount(service: dict, path: str) -> dict:
    matches: list[dict] = []
    for volume in service.get("volumes") or []:
        if not isinstance(volume, dict):
            raise AuditError("effective Compose volume không ở long syntax")
        target = volume.get("target")
        if isinstance(target, str) and (
            path == target.rstrip("/") or path.startswith(target.rstrip("/") + "/")
        ):
            matches.append(volume)
    if len(matches) != 1:
        raise AuditError(f"cần đúng một Compose mount bao phủ {path}")
    return matches[0]


def _covering_runtime_mount(item: dict, path: str) -> dict:
    matches = []
    mounts = item.get("Mounts") or []
    if not isinstance(mounts, list):
        raise AuditError("container mounts không parse được")
    for mount in mounts:
        if not isinstance(mount, dict):
            raise AuditError("container mount không phải mapping")
        target = mount.get("Destination")
        if isinstance(target, str) and (
            path == target.rstrip("/") or path.startswith(target.rstrip("/") + "/")
        ):
            matches.append(mount)
    if len(matches) != 1:
        raise AuditError(f"cần đúng một runtime mount bao phủ {path}")
    return matches[0]


def _verify_mount_identity(item: dict, service: dict, path: str) -> None:
    compose_mount = _covering_compose_mount(service, path)
    runtime_mount = _covering_runtime_mount(item, path)
    if compose_mount.get("target") != runtime_mount.get("Destination"):
        raise AuditError("Compose/runtime mount destination không khớp")
    compose_source = compose_mount.get("source")
    runtime_source = runtime_mount.get("Source")
    if not isinstance(compose_source, str) or not isinstance(runtime_source, str):
        raise AuditError("Compose/runtime mount source không parse được")
    if os.path.isabs(compose_source):
        same_source = os.path.realpath(compose_source) == os.path.realpath(runtime_source)
    else:
        same_source = compose_source == runtime_mount.get("Name")
    if not same_source:
        raise AuditError("Compose/runtime mount source identity không khớp")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix-dir", required=True)
    parser.add_argument("--compose-file", required=True)
    parser.add_argument("--synapse-container", required=True)
    parser.add_argument("--synapse-service", required=True)
    parser.add_argument("--nginx-container", required=True)
    parser.add_argument("--nginx-service", required=True)
    parser.add_argument("--config-path", required=True)
    parser.add_argument("--nginx-config-path", required=True)
    args = parser.parse_args()
    try:
        running_ids = [line for line in run("docker", "ps", "-q").splitlines() if line]
        if not running_ids:
            raise AuditError("không có running container")
        documents = json.loads(run("docker", "inspect", *running_ids))
        if not isinstance(documents, list):
            raise AuditError("docker inspect không trả list")

        by_name = {
            str(item.get("Name", "")).lstrip("/"): item
            for item in documents
            if isinstance(item, dict)
        }
        synapse = by_name.get(args.synapse_container)
        nginx = by_name.get(args.nginx_container)
        if synapse is None or nginx is None:
            raise AuditError("không thấy đúng container Synapse/nginx đang chạy")

        def looks_like_synapse(item: dict) -> bool:
            config = item.get("Config") or {}
            probe = " ".join(
                [
                    str(config.get("Image", "")),
                    str(item.get("Path", "")),
                    *[str(value) for value in (item.get("Args") or [])],
                ]
            ).lower()
            return "synapse" in probe

        synapse_like = [item for item in documents if looks_like_synapse(item)]
        if len(synapse_like) != 1 or synapse_like[0].get("Id") != synapse.get("Id"):
            raise AuditError("phải có đúng một running Synapse-like container")

        # Docker names/images are not enough: reject a second host/container
        # Synapse process even if its service/container name is arbitrary.
        designated_id = str(synapse.get("Id"))
        host_synapse_processes = []
        host_proxy_processes = []
        for entry in os.listdir("/proc"):
            if not entry.isdigit():
                continue
            try:
                command = open(f"/proc/{entry}/cmdline", "rb").read().replace(b"\0", b" ")
                cgroup = open(f"/proc/{entry}/cgroup", "r", encoding="utf-8").read()
            except (OSError, UnicodeError):
                continue
            if any(
                marker in command
                for marker in (
                    b"synapse.app.homeserver",
                    b"synapse.app.generic_worker",
                    b"synapse_homeserver",
                    b"synapse_worker",
                )
            ):
                host_synapse_processes.append((entry, cgroup))
            argv = [part for part in command.split(b" ") if part]
            executable = (
                os.path.basename(os.fsdecode(argv[0])).rstrip(":") if argv else ""
            )
            if executable in {
                "nginx",
                "caddy",
                "haproxy",
                "traefik",
                "envoy",
                "apache2",
                "httpd",
                "cloudflared",
                "socat",
                "rinetd",
            }:
                host_proxy_processes.append((entry, executable, cgroup))
        if len(host_synapse_processes) != 1:
            raise AuditError("host phải thấy đúng một Synapse process")
        if designated_id not in host_synapse_processes[0][1] \
                and designated_id[:12] not in host_synapse_processes[0][1]:
            raise AuditError("Synapse process không thuộc designated container cgroup")
        nginx_id = str(nginx.get("Id"))
        if not host_proxy_processes or any(
            nginx_id not in cgroup and nginx_id[:12] not in cgroup
            for _, _, cgroup in host_proxy_processes
        ):
            raise AuditError("host có reverse-proxy process ngoài designated nginx cgroup")

        project = _require_compose_identity(
            synapse,
            service_name=args.synapse_service,
            matrix_dir=args.matrix_dir,
            compose_file=args.compose_file,
        )
        nginx_project = _require_compose_identity(
            nginx,
            service_name=args.nginx_service,
            matrix_dir=args.matrix_dir,
            compose_file=args.compose_file,
        )
        if nginx_project != project:
            raise AuditError("nginx/Synapse không cùng designated Compose project")
        rendered = json.loads(
            run(
                "docker",
                "compose",
                "--project-directory",
                args.matrix_dir,
                "-f",
                args.compose_file,
                "config",
                "--format",
                "json",
            )
        )
        services = rendered.get("services") if isinstance(rendered, dict) else None
        networks_config = rendered.get("networks") if isinstance(rendered, dict) else None
        if not isinstance(services, dict) or set(
            (args.synapse_service, args.nginx_service)
        ) - set(services):
            raise AuditError("không parse được toàn bộ effective Compose services")
        if not isinstance(networks_config, dict):
            raise AuditError("không parse được effective Compose networks")
        synapse_service = services[args.synapse_service]
        nginx_service = services[args.nginx_service]
        if not isinstance(synapse_service, dict) or not isinstance(nginx_service, dict):
            raise AuditError("designated Compose services không phải mapping")
        for item, service, label in (
            (synapse, synapse_service, "Synapse"),
            (nginx, nginx_service, "nginx"),
        ):
            image = service.get("image")
            runtime_image = (item.get("Config") or {}).get("Image")
            if not isinstance(image, str) or image != runtime_image:
                raise AuditError(f"{label} effective/runtime image identity không khớp")
        _verify_mount_identity(synapse, synapse_service, args.config_path)
        _verify_mount_identity(nginx, nginx_service, args.nginx_config_path)

        synapse_service_networks = _service_networks(synapse_service)
        nginx_service_networks = _service_networks(nginx_service)
        if len(synapse_service_networks) != 1:
            raise AuditError("Synapse service phải có đúng một origin network")
        logical_network = next(iter(synapse_service_networks))
        if logical_network not in nginx_service_networks:
            raise AuditError("nginx service không thuộc designated origin network")
        logical_config = networks_config.get(logical_network)
        if not isinstance(logical_config, dict) or logical_config.get("internal") is not True:
            raise AuditError("Compose origin network phải internal: true")
        expected_network_name = logical_config.get("name")
        if not isinstance(expected_network_name, str) or not expected_network_name:
            raise AuditError("Compose origin network thiếu effective name")
        for name, service in services.items():
            if not isinstance(service, dict):
                raise AuditError("Compose service không phải mapping")
            probe = " ".join(
                [
                    str(service.get("image", "")),
                    str(service.get("command", "")),
                    str(service.get("entrypoint", "")),
                ]
            ).lower()
            if name in {args.synapse_service, args.nginx_service}:
                if "synapse" not in probe:
                    if name == args.synapse_service:
                        raise AuditError("designated Compose service không chứng minh là Synapse")
                continue
            networks = service.get("networks") or {}
            if isinstance(networks, list):
                network_values = networks
            elif isinstance(networks, dict):
                network_values = list(networks.values())
            else:
                raise AuditError("Compose service networks không parse được")
            aliases = []
            for detail in network_values:
                if isinstance(detail, dict):
                    aliases.extend(detail.get("aliases") or [])
            volumes = service.get("volumes") or []
            mounts_designated_config = any(
                isinstance(volume, dict) and volume.get("target") == args.config_path
                for volume in volumes
            )
            suspicious_ports = any(
                isinstance(port, dict) and port.get("target") in (8008, 8009, 8448)
                for port in (service.get("ports") or [])
            )
            if (
                "synapse" in probe
                or "worker" in str(name).lower()
                or "synapse" in aliases
                or mounts_designated_config
                or suspicious_ports
            ):
                raise AuditError("Compose có secondary Synapse/client-origin candidate")
        def compose_service_ids(service_name: str) -> set[str]:
            return set(
                run(
                    "docker",
                    "compose",
                    "--project-directory",
                    args.matrix_dir,
                    "-f",
                    args.compose_file,
                    "ps",
                    "-q",
                    service_name,
                ).splitlines()
            )

        if compose_service_ids(args.synapse_service) != {designated_id}:
            raise AuditError("Synapse container ID không khớp duy nhất Compose service")
        if compose_service_ids(args.nginx_service) != {nginx_id}:
            raise AuditError("nginx container ID không khớp duy nhất Compose service")

        synapse_networks = (synapse.get("NetworkSettings") or {}).get("Networks") or {}
        nginx_networks = (nginx.get("NetworkSettings") or {}).get("Networks") or {}
        if not isinstance(synapse_networks, dict) or len(synapse_networks) != 1:
            raise AuditError("Synapse phải ở đúng một reviewed Docker network")
        network_name, network_detail = next(iter(synapse_networks.items()))
        if network_name != expected_network_name:
            raise AuditError("runtime origin network không khớp effective Compose name")
        if network_name not in nginx_networks:
            raise AuditError("nginx và Synapse không cùng reviewed Docker network")
        aliases = (network_detail or {}).get("Aliases")
        if not isinstance(aliases, list) or "synapse" not in aliases:
            raise AuditError("designated Synapse không sở hữu alias literal 'synapse'")
        alias_claimants = []
        for item in documents:
            detail = ((item.get("NetworkSettings") or {}).get("Networks") or {}).get(
                network_name
            )
            if isinstance(detail, dict) and "synapse" in (detail.get("Aliases") or []):
                alias_claimants.append(item.get("Id"))
        if alias_claimants != [synapse.get("Id")]:
            raise AuditError("Docker alias 'synapse' không thuộc duy nhất designated origin")

        network_documents = json.loads(run("docker", "network", "inspect", network_name))
        if not isinstance(network_documents, list) or len(network_documents) != 1:
            raise AuditError("docker network inspect không trả một origin network")
        network_document = network_documents[0]
        if not isinstance(network_document, dict) \
                or network_document.get("Internal") is not True:
            raise AuditError("runtime origin network không phải internal")
        peers = network_document.get("Containers")
        if not isinstance(peers, dict) or set(peers) != {designated_id, nginx_id}:
            raise AuditError("origin network peer set phải chính xác nginx + Synapse")
        network_labels = network_document.get("Labels") or {}
        if not isinstance(network_labels, dict) \
                or network_labels.get("com.docker.compose.project") != project:
            raise AuditError("origin network không thuộc designated Compose project")

        # Detect common alternate ingress containers and host proxy/listener
        # ownership.  The independent SSH vantage probe in the rollout scripts
        # remains mandatory because a point-in-time process scan cannot prove
        # an external DNS/CDN/LB path by itself.
        for item in documents:
            item_id = str(item.get("Id"))
            ports = ((item.get("NetworkSettings") or {}).get("Ports") or {})
            if not isinstance(ports, dict):
                raise AuditError("container published ports không parse được")
            for bindings in ports.values():
                if bindings is None:
                    continue
                if not isinstance(bindings, list):
                    raise AuditError("container port bindings không parse được")
                for binding in bindings:
                    if not isinstance(binding, dict):
                        raise AuditError("container port binding không phải mapping")
                    host_port = str(binding.get("HostPort", ""))
                    if host_port in {"80", "443", "8448"} and item_id != nginx_id:
                        raise AuditError("container khác publish public Matrix/HTTPS port")
                    if host_port == "8008" and item_id != designated_id:
                        raise AuditError("container khác chiếm Synapse loopback port")
        ss_output = run("ss", "-H", "-ltnp", "sport = :8008")
        for line in ss_output.splitlines():
            if not line.strip():
                continue
            if not any(value in line for value in ("127.0.0.1:8008", "[::1]:8008")):
                raise AuditError("host listener 8008 không bind loopback")
            if "docker-proxy" not in line and "dockerd" not in line:
                raise AuditError("host listener 8008 không thuộc Docker publish path")

        runtime_probe = r'''
import json, os, sys
expected = sys.argv[1]
if os.path.islink(expected) or os.path.realpath(expected) != expected or not os.path.isfile(expected):
    raise SystemExit("effective config path không phải regular non-symlink exact path")
homeservers = []
workers = []
for entry in os.listdir("/proc"):
    if not entry.isdigit():
        continue
    try:
        raw = open(f"/proc/{entry}/cmdline", "rb").read()
    except OSError:
        continue
    argv = [part.decode("utf-8", "strict") for part in raw.split(b"\0") if part]
    joined = " ".join(argv)
    if "synapse.app.generic_worker" in joined or "synapse_worker" in joined:
        workers.append(argv)
    if "synapse.app.homeserver" in joined or "synapse_homeserver" in joined:
        homeservers.append(argv)
if workers or len(homeservers) != 1:
    raise SystemExit("runtime phải có đúng một homeserver process và không worker")
argv = homeservers[0]
paths = []
index = 0
while index < len(argv):
    value = argv[index]
    if value in ("-c", "--config-path"):
        if index + 1 >= len(argv):
            raise SystemExit("config flag thiếu value")
        paths.append(argv[index + 1])
        index += 2
        continue
    if value.startswith("--config-path="):
        paths.append(value.split("=", 1)[1])
    index += 1
if paths != [expected]:
    raise SystemExit("runtime config paths không đúng duy nhất designated file")
print(json.dumps({"homeserver_processes": 1, "config_paths": paths}))
'''
        output = run(
            "docker",
            "exec",
            "-i",
            args.synapse_container,
            "python",
            "-",
            args.config_path,
            input_text=runtime_probe,
        )
        parsed = json.loads(output)
        if parsed != {"homeserver_processes": 1, "config_paths": [args.config_path]}:
            raise AuditError("runtime probe output không đúng contract")
    except (AuditError, json.JSONDecodeError, OSError, UnicodeError) as error:
        print(f"Fail-closed Synapse runtime topology: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
