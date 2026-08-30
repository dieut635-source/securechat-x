#!/usr/bin/env python3
"""Fail-closed audit of effective ``nginx -T`` routing.

The lexer/parser is independent of line layout, so inline and multiline nginx
directives cannot evade checks. Only the reviewed SecureChat direct-proxy shape
is accepted; unknown routing fails instead of being guessed.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path


class TopologyError(RuntimeError):
    pass


@dataclass(frozen=True)
class Node:
    name: str
    args: tuple[str, ...]
    children: tuple["Node", ...] | None


def tokenize(value: str) -> list[str]:
    tokens: list[str] = []
    current: list[str] = []
    quote: str | None = None
    escaped = False
    in_comment = False

    def flush() -> None:
        if current:
            tokens.append("".join(current))
            current.clear()

    for char in value:
        if in_comment:
            if char == "\n":
                in_comment = False
            continue
        if quote is not None:
            if escaped:
                current.append(char)
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            else:
                current.append(char)
            continue
        if escaped:
            current.append(char)
            escaped = False
        elif char == "\\":
            escaped = True
        elif char == "#":
            flush()
            in_comment = True
        elif char in ("'", '"'):
            quote = char
        elif char in "{};":
            flush()
            tokens.append(char)
        elif char.isspace():
            flush()
        else:
            current.append(char)
    if quote is not None or escaped:
        raise TopologyError("quoted string/escape chưa đóng")
    flush()
    return tokens


def parse_nodes(tokens: list[str]) -> tuple[Node, ...]:
    position = 0

    def parse_sequence(expect_closing: bool) -> tuple[Node, ...]:
        nonlocal position
        result: list[Node] = []
        while position < len(tokens):
            if tokens[position] == "}":
                if not expect_closing:
                    raise TopologyError("dấu } ngoài block")
                position += 1
                return tuple(result)
            header: list[str] = []
            while position < len(tokens) and tokens[position] not in ("{", "}", ";"):
                header.append(tokens[position])
                position += 1
            if not header or position >= len(tokens):
                raise TopologyError("directive nginx thiếu tên hoặc terminator")
            terminator = tokens[position]
            position += 1
            if terminator == "}":
                raise TopologyError("directive nginx thiếu ; trước }")
            name, *args = header
            children = None if terminator == ";" else parse_sequence(True)
            result.append(Node(name, tuple(args), children))
        if expect_closing:
            raise TopologyError("nginx block chưa đóng")
        return tuple(result)

    result = parse_sequence(False)
    if position != len(tokens):
        raise TopologyError("nginx token dư sau parse")
    return result


def descendants(nodes: tuple[Node, ...]) -> list[Node]:
    result: list[Node] = []
    for node in nodes:
        result.append(node)
        if node.children is not None:
            result.extend(descendants(node.children))
    return result


def direct(node: Node, name: str) -> list[Node]:
    return [item for item in (node.children or ()) if item.name == name]


def select_public_server(nodes: tuple[Node, ...], server_name: str) -> Node:
    servers = [
        node
        for node in descendants(nodes)
        if node.name == "server" and node.children is not None
    ]
    selected: list[Node] = []
    for server in servers:
        names = direct(server, "server_name")
        listens = direct(server, "listen")
        has_name = len(names) == 1 and server_name in names[0].args
        has_https = any(
            bool(item.args)
            and (item.args[0] == "443" or item.args[0].endswith(":443"))
            for item in listens
        )
        if has_name and has_https:
            selected.append(server)
    if len(selected) != 1:
        raise TopologyError(
            f"cần đúng một HTTPS server block cho {server_name}; thấy {len(selected)}"
        )
    return selected[0]


def is_exact_http_redirect(server: Node, server_name: str) -> bool:
    """Accept only the inert HTTP->HTTPS companion vhost.

    Rejecting every other externally listening vhost is deliberate: a static
    alternate vhost (or one using a scripting content handler) can expose a
    second Matrix/Web ingress without containing a ``*_pass`` directive.
    """

    children = server.children or ()
    if not children or any(
        child.children is not None
        or child.name not in {"listen", "server_name", "return"}
        for child in children
    ):
        return False
    listens = direct(server, "listen")
    names = direct(server, "server_name")
    returns = direct(server, "return")
    allowed_listens = {
        ("80",),
        ("80", "default_server"),
        ("0.0.0.0:80",),
        ("0.0.0.0:80", "default_server"),
        ("[::]:80",),
        ("[::]:80", "default_server"),
    }
    allowed_targets = {f"https://{server_name}$request_uri"}
    return (
        bool(listens)
        and all(item.args in allowed_listens for item in listens)
        and len(names) == 1
        and names[0].args == (server_name,)
        and len(returns) == 1
        and len(returns[0].args) == 2
        and returns[0].args[0] in {"301", "308"}
        and returns[0].args[1] in allowed_targets
    )


def assert_closed_location(
    all_servers: list[Node], public: Node, path: str, modifier: str
) -> None:
    expected_args = (modifier, path)
    matches = [
        node
        for server in all_servers
        for node in descendants(server.children or ())
        if node.name == "location" and node.args == expected_args
    ]
    inside = [
        node
        for node in descendants(public.children or ())
        if node.name == "location" and node.args == expected_args
    ]
    if len(matches) != 1 or len(inside) != 1:
        raise TopologyError(f"location đóng không duy nhất: {modifier} {path}")
    expected_body = (Node("return", ("404",), None),)
    if inside[0].children != expected_body:
        raise TopologyError(
            f"location deny phải có đúng direct child 'return 404': {modifier} {path}"
        )


def iter_proxy_routes(nodes: tuple[Node, ...], location: Node | None = None):
    for node in nodes:
        current_location = node if node.name == "location" else location
        if node.name in {
            "proxy_pass",
            "grpc_pass",
            "fastcgi_pass",
            "uwsgi_pass",
            "scgi_pass",
        }:
            yield node, current_location
        if node.children is not None:
            yield from iter_proxy_routes(node.children, current_location)


def iter_with_location(nodes: tuple[Node, ...], location: Node | None = None):
    for node in nodes:
        if node.name == "location" and location is not None:
            raise TopologyError("nested location chưa được review")
        current_location = node if node.name == "location" else location
        yield node, current_location
        if node.children is not None:
            yield from iter_with_location(node.children, current_location)


# These are the only generic Matrix routing locations accepted by this rollout.
# Any more-specific /login, /register or Admin location containing proxy_pass is
# rejected even if the standard deny blocks also exist.
MATRIX_PROXY_LOCATIONS = {
    ("/_matrix",),
    ("/_matrix/",),
    ("^~", "/_matrix/"),
    ("/_synapse/client",),
    ("/_synapse/client/",),
    ("^~", "/_synapse/client/"),
    ("~", "^/_matrix/(federation|key)/"),
}
OPEN_LOGIN_PROXY_LOCATIONS = {
    ("=", f"/_matrix/client/{version}/login")
    for version in ("v3", "r0", "unstable", "api/v1")
}


def audit(
    value: str,
    server_name: str,
    require_closed_auth: bool,
    require_admin_block: bool = True,
) -> None:
    # nginx -T may mix diagnostic stderr with config stdout. Remove only its
    # fixed diagnostic prefix, never arbitrary config-shaped lines.
    filtered = "\n".join(
        line for line in value.splitlines() if not line.startswith("nginx: ")
    )
    nodes = parse_nodes(tokenize(filtered))
    all_nodes = descendants(nodes)
    if any(node.name == "upstream" for node in all_nodes):
        raise TopologyError("upstream alias không được hỗ trợ; cần direct target đã review")

    servers = [
        node
        for node in all_nodes
        if node.name == "server" and node.children is not None
    ]
    public = select_public_server(nodes, server_name)
    for server in servers:
        if server is public:
            continue
        if not is_exact_http_redirect(server, server_name):
            raise TopologyError(
                "vhost khác không phải HTTP->HTTPS redirect exact đã review"
            )

    public_nodes = descendants(public.children or ())
    forbidden_dynamic = {
        "if",
        "rewrite",
        "error_page",
        "auth_request",
        "mirror",
        "set",
        "eval",
        "perl",
    }
    if any(
        node.name in forbidden_dynamic
        or "lua" in node.name.lower()
        or node.name.startswith("js_")
        or node.name.startswith("perl_")
        for node in public_nodes
    ):
        raise TopologyError("public vhost có conditional/script/internal routing chưa review")

    # The public static surface has one server-level absolute root.  The only
    # alias is the exact Matrix client discovery document and must live below
    # that root.  This rules out a second filesystem/content surface while
    # still supporting both the legacy root in the report phase and the
    # neutral root after rollout.
    roots = direct(public, "root")
    if (
        len(roots) != 1
        or len(roots[0].args) != 1
        or not roots[0].args[0].startswith("/")
        or "$" in roots[0].args[0]
    ):
        raise TopologyError("public vhost phải có đúng một static root absolute")
    static_root = roots[0].args[0].rstrip("/")
    aliases: list[Node] = []
    for node, location in iter_with_location(public.children or ()):
        if node.name == "root" and location is not None:
            raise TopologyError("root trong location chưa được review")
        if node.name == "alias":
            if (
                location is None
                or location.args != ("=", "/.well-known/matrix/client")
                or len(node.args) != 1
                or "$" in node.args[0]
                or not node.args[0].startswith(static_root + "/")
            ):
                raise TopologyError("alias public ngoài .well-known static chưa review")
            aliases.append(node)
    if len(aliases) != 1:
        raise TopologyError("cần đúng một alias static .well-known đã review")

    deny_location_args = {("^~", "/_synapse/admin/")}
    for version in ("v3", "r0", "unstable", "api/v1"):
        for endpoint in ("login", "register"):
            base = f"/_matrix/client/{version}/{endpoint}"
            deny_location_args.add(("=", base))
            deny_location_args.add(("^~", base + "/"))
    for node, location in iter_with_location(public.children or ()):
        if node.name == "return":
            if location is not None and location.args in deny_location_args \
                    and node.args == ("404",):
                continue
            if location is not None and location.args == ("=", "/livekit/sfu") \
                    and node.args == ("301", "https://$host/livekit/sfu/"):
                continue
            raise TopologyError("public vhost có return ngoài allowlist chính xác")
        if node.name == "try_files":
            if location is None or location.args != ("/",) \
                    or node.args != ("$uri", "$uri/", "=404"):
                raise TopologyError("try_files có internal fallback chưa review")

    matrix_proxy_count = 0
    for proxy, location in iter_proxy_routes(public.children or ()):
        if proxy.name != "proxy_pass":
            raise TopologyError(f"proxy protocol chưa review: {proxy.name}")
        if location is None or len(proxy.args) != 1:
            raise TopologyError("proxy_pass thiếu location hoặc target literal duy nhất")
        target = proxy.args[0]
        if "$" in target:
            raise TopologyError("proxy_pass dùng biến chưa review")
        if location.args in MATRIX_PROXY_LOCATIONS and target in {
            "http://synapse:8008",
            "http://synapse:8008/",
        }:
            matrix_proxy_count += 1
            continue
        if not require_closed_auth and location.args in OPEN_LOGIN_PROXY_LOCATIONS \
                and target in {"http://synapse:8008", "http://synapse:8008/"}:
            matrix_proxy_count += 1
            continue
        if any("/livekit/sfu" in arg for arg in location.args) and target.startswith("http://") \
                and target.endswith(":7880/"):
            authority = target[len("http://") : -len(":7880/")]
            if authority and all(char.isalnum() or char in "._:-" for char in authority):
                continue
        if any("/livekit/jwt" in arg for arg in location.args) \
                and target == "http://lk-jwt:8080/":
            continue
        raise TopologyError(
            f"proxy route chưa review: location {' '.join(location.args)} -> {target}"
        )
    if matrix_proxy_count < 1:
        raise TopologyError("không thấy direct Matrix proxy location đã review")

    if require_admin_block:
        assert_closed_location(servers, public, "/_synapse/admin/", "^~")
    if require_closed_auth:
        for version in ("v3", "r0", "unstable", "api/v1"):
            for endpoint in ("login", "register"):
                base = f"/_matrix/client/{version}/{endpoint}"
                assert_closed_location(servers, public, base, "=")
                assert_closed_location(servers, public, base + "/", "^~")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--server-name", required=True)
    parser.add_argument("--require-closed-auth", action="store_true")
    parser.add_argument("--allow-missing-admin", action="store_true")
    args = parser.parse_args()
    try:
        value = Path(args.config).read_text(encoding="utf-8")
        audit(
            value,
            args.server_name,
            args.require_closed_auth,
            require_admin_block=not args.allow_missing_admin,
        )
    except (OSError, UnicodeError, TopologyError) as error:
        print(f"Fail-closed nginx topology: {error}")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
