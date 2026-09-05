#!/usr/bin/env bash

set -euo pipefail

dump_file=${1:?Usage: check_securechat_aapt_dump.sh <aapt-badging-dump>}

grep -Eq "^package: name='com\\.securechat\\.app'([[:space:]]|$)" "$dump_file" || {
    printf 'Unexpected release application ID in %s.\n' "$dump_file" >&2
    exit 1
}

grep -Eq "^application-label:'SecureChat'$" "$dump_file" || {
    printf 'SecureChat application label is missing from %s.\n' "$dump_file" >&2
    exit 1
}

if grep -E '^application-label(-[^:]*)?:' "$dump_file" | \
    grep -Ev "^application-label(-[^:]*)?:'SecureChat'$"; then
    printf 'Unexpected localized application label found in %s.\n' "$dump_file" >&2
    exit 1
fi

grep -Eq "^application: label='SecureChat'([[:space:]]|$)" "$dump_file" || {
    printf 'SecureChat resolved application label is missing from %s.\n' "$dump_file" >&2
    exit 1
}

if grep -E '^application:' "$dump_file" | \
    grep -Ev "^application: label='SecureChat'([[:space:]]|$)"; then
    printf 'Unexpected resolved application label found in %s.\n' "$dump_file" >&2
    exit 1
fi

# Launcher labels and literal component labels are externally visible even when the application
# label itself is correct. Resource-reference labels are covered by the localized app-label checks;
# direct literals are checked in the appended manifest tree.
if grep -E '^(launchable-activity|activity-alias):' "$dump_file" | \
    grep -Ei "label='[^']*(Element|Vector|Riot)[^']*'"; then
    printf 'Inherited branding found in a launchable component label in %s.\n' "$dump_file" >&2
    exit 1
fi
if grep -Ei '^[[:space:]]*A: android:label.*="[^"]*(Element|Vector|Riot)[^"]*"' "$dump_file"; then
    printf 'Inherited branding found in a literal component label in %s.\n' "$dump_file" >&2
    exit 1
fi
if grep -Ei '^[[:space:]]*A: android:taskAffinity.*="io\.element[^"]*"' "$dump_file"; then
    printf 'Inherited runtime task affinity found in %s.\n' "$dump_file" >&2
    exit 1
fi

# Firebase is deliberately excluded from SecureChat builds. Scan both `aapt dump badging` and the
# appended AndroidManifest.xml tree so a transitive permission, provider, service, or intent action
# cannot silently re-enable inherited push infrastructure.
if grep -Eni -- \
    'com\.google\.android\.c2dm|com\.google\.firebase|firebase_messaging_|MESSAGING_EVENT|FirebaseMessagingService|io\.element\.android\.libraries\.pushproviders\.firebase' \
    "$dump_file"; then
    printf 'Firebase/FCM manifest residue found in %s.\n' "$dump_file" >&2
    exit 1
fi

# Preview tooling and component-catalog activities are not part of the production attack surface.
if grep -En -- \
    'androidx\.compose\.ui\.tooling\.PreviewActivity|com\.airbnb\.android\.showkase\.ui\.ShowkaseBrowserActivity' \
    "$dump_file"; then
    printf 'Development-only Activity found in %s.\n' "$dump_file" >&2
    exit 1
fi

# WorkManager diagnostics and ProfileInstaller broadcast controls are intentionally removed from
# release. ADB shell holds the DUMP permission, so keeping either receiver would let the
# workstation used for sideloading request internal work metadata or profile operations.
if grep -En -- \
    'androidx\.work\.impl\.diagnostics\.DiagnosticsReceiver|androidx\.profileinstaller\.ProfileInstallReceiver|androidx\.work\.diagnostics\.REQUEST_DIAGNOSTICS|androidx\.profileinstaller\.action\.' \
    "$dump_file"; then
    printf 'Diagnostic/profile broadcast surface found in %s.\n' "$dump_file" >&2
    exit 1
fi

# The closed build deliberately has no public push distributor. Reject application components,
# connector actions, and package-query residue so a dependency cannot silently restore it.
if grep -Eni -- \
    'org\.unifiedpush\.android|io\.element\.android\.libraries\.pushproviders\.unifiedpush|VectorUnifiedPushMessagingReceiver|KeepInternalDistributor' \
    "$dump_file"; then
    printf 'UnifiedPush manifest residue found in %s.\n' "$dump_file" >&2
    exit 1
fi

# SecureChat must never install application packages received through chat. Check the final APK
# rather than only source manifests so a transitive dependency cannot silently restore the power.
if grep -En -- 'android\.permission\.REQUEST_INSTALL_PACKAGES' "$dump_file"; then
    printf 'Package-install permission found in %s.\n' "$dump_file" >&2
    exit 1
fi

# Validate security-sensitive application flags in the packaged manifest. `debuggable` and
# `testOnly` are normally omitted when false, so only reject an explicit true value. Backups must
# be explicitly disabled, while any explicit cleartext opt-in is forbidden.
if ! grep -Eq -- 'android:allowBackup\([^)]*\)=\(type 0x12\)0x0' "$dump_file"; then
    printf 'Application backup is not explicitly disabled in %s.\n' "$dump_file" >&2
    exit 1
fi
if ! grep -Eq -- 'android:usesCleartextTraffic\([^)]*\)=\(type 0x12\)0x0' "$dump_file"; then
    printf 'Cleartext traffic is not explicitly disabled in %s.\n' "$dump_file" >&2
    exit 1
fi
if ! grep -Eq -- 'android:networkSecurityConfig\([^)]*\)=@0x[0-9a-f]+' "$dump_file"; then
    printf 'Packaged network security configuration is missing in %s.\n' "$dump_file" >&2
    exit 1
fi
if grep -Eq -- 'android:(debuggable|testOnly|usesCleartextTraffic)\([^)]*\)=\(type 0x12\)0xffffffff' "$dump_file"; then
    printf 'Debug, test-only, or cleartext application flag enabled in %s.\n' "$dump_file" >&2
    exit 1
fi

# The closed SecureChat APK does not integrate with Google/Android Auto. Reject the metadata in the
# packaged manifest so it cannot be restored accidentally by an application or library manifest.
if grep -En -- 'com\.google\.android\.gms\.car\.application' "$dump_file"; then
    printf 'Android Auto integration metadata found in %s.\n' "$dump_file" >&2
    exit 1
fi

# Validate the compiled XML resources, not merely their source counterparts. Resolve the exact
# resource IDs in the packaged manifest (including aliases and qualified variants) so a safe XML
# file with the expected source-era name cannot act as a decoy for a different referenced policy.
python3 - "$dump_file" <<'PY'
import re
import sys
from pathlib import Path

dump_path = Path(sys.argv[1])
lines = dump_path.read_text(encoding="utf-8", errors="replace").splitlines()
sections = {}
xml_resource_table = None
packaged_xml = None
current_kind = None
current_name = None
current_lines = None


def start_marker(kind, name=None):
    global current_kind, current_name, current_lines
    if current_kind is not None:
        raise SystemExit(f"Nested packaged-resource marker in {dump_path}")
    current_kind = kind
    current_name = name
    current_lines = []


def end_marker(kind, name=None):
    global current_kind, current_name, current_lines, xml_resource_table, packaged_xml
    if current_kind != kind or current_name != name:
        raise SystemExit(f"Mismatched packaged-resource marker in {dump_path}")
    if kind == "resource":
        if name in sections:
            raise SystemExit(f"Duplicate packaged-resource section in {dump_path}: {name}")
        sections[name] = current_lines
    elif kind == "resource-table":
        if xml_resource_table is not None:
            raise SystemExit(f"Duplicate XML resource table in {dump_path}")
        xml_resource_table = current_lines
    elif kind == "packaged-xml":
        if packaged_xml is not None:
            raise SystemExit(f"Duplicate packaged XML list in {dump_path}")
        packaged_xml = current_lines
    current_kind = None
    current_name = None
    current_lines = None


for line in lines:
    if line.startswith("SECURECHAT_AAPT_RESOURCE_BEGIN "):
        start_marker("resource", line.removeprefix("SECURECHAT_AAPT_RESOURCE_BEGIN "))
    elif line.startswith("SECURECHAT_AAPT_RESOURCE_END "):
        end_marker("resource", line.removeprefix("SECURECHAT_AAPT_RESOURCE_END "))
    elif line == "SECURECHAT_AAPT_XML_RESOURCE_TABLE_BEGIN":
        start_marker("resource-table")
    elif line == "SECURECHAT_AAPT_XML_RESOURCE_TABLE_END":
        end_marker("resource-table")
    elif line == "SECURECHAT_AAPT_PACKAGED_XML_BEGIN":
        start_marker("packaged-xml")
    elif line == "SECURECHAT_AAPT_PACKAGED_XML_END":
        end_marker("packaged-xml")
    elif line.startswith("SECURECHAT_AAPT_"):
        raise SystemExit(f"Unknown packaged-resource marker in {dump_path}: {line}")
    elif current_kind is not None:
        current_lines.append(line)

if current_kind is not None:
    raise SystemExit(f"Unclosed packaged-resource marker in {dump_path}")
if xml_resource_table is None or packaged_xml is None:
    raise SystemExit(f"Compiled XML resource evidence is missing from {dump_path}")

packaged_xml_pattern = re.compile(r"^res/(?:[A-Za-z0-9_+.-]+/)*[A-Za-z0-9_+.-]+\.xml$")
if (
    not packaged_xml
    or any(not packaged_xml_pattern.fullmatch(path) for path in packaged_xml)
    or any(
        component in {".", ".."}
        for path in packaged_xml
        for component in path.split("/")
    )
):
    raise SystemExit(f"Invalid packaged XML list in {dump_path}")
if packaged_xml != sorted(packaged_xml) or len(packaged_xml) != len(set(packaged_xml)):
    raise SystemExit(f"Packaged XML list must be sorted and contain no duplicates in {dump_path}")

expected_sections = {"AndroidManifest.xml", *packaged_xml}
if set(sections) != expected_sections:
    raise SystemExit(
        f"Packaged XML audit is incomplete in {dump_path}: expected {sorted(expected_sections)}, "
        f"got {sorted(sections)}"
    )


def normalized_resource_id(value):
    match = re.fullmatch(r"0x([0-9a-fA-F]{8})", value)
    if not match:
        raise SystemExit(f"Invalid compiled resource ID in {dump_path}: {value}")
    return f"0x{match.group(1).lower()}"


resource_specs = {}
resource_values = {}
pending_path_id = None
spec_pattern = re.compile(
    r"^\s*spec resource (0x[0-9a-fA-F]{8}) ([^:\s]+):xml/([a-z0-9_]+): flags=0x[0-9a-fA-F]+$"
)
value_pattern = re.compile(
    r"^\s*resource (0x[0-9a-fA-F]{8}) ([^:\s]+):xml/([a-z0-9_]+): "
    r"t=0x([0-9a-fA-F]{2}) d=0x([0-9a-fA-F]{8}) \(s=0x[0-9a-fA-F]+ r=0x[0-9a-fA-F]+\)$"
)
path_pattern = re.compile(
    r'^\s*\(string(?:8|16)\) "(res/(?:[A-Za-z0-9_+.-]+/)*[A-Za-z0-9_+.-]+\.xml)"$'
)
for line in xml_resource_table:
    if pending_path_id is not None:
        path_match = path_pattern.fullmatch(line)
        if not path_match:
            raise SystemExit(
                f"Missing backing file for compiled XML resource {pending_path_id} in {dump_path}"
            )
        resource_values.setdefault(pending_path_id, []).append(("path", path_match.group(1)))
        pending_path_id = None
        continue
    spec_match = spec_pattern.fullmatch(line)
    if spec_match:
        resource_id = normalized_resource_id(spec_match.group(1))
        spec = (spec_match.group(2), spec_match.group(3))
        if resource_id in resource_specs:
            raise SystemExit(f"Duplicate compiled XML resource spec for {resource_id} in {dump_path}")
        resource_specs[resource_id] = spec
        continue
    value_match = value_pattern.fullmatch(line)
    if value_match:
        resource_id = normalized_resource_id(value_match.group(1))
        row_spec = (value_match.group(2), value_match.group(3))
        if resource_specs.get(resource_id) != row_spec:
            raise SystemExit(f"Compiled XML resource row does not match its spec: {resource_id}")
        value_type = value_match.group(4).lower()
        value_data = normalized_resource_id(f"0x{value_match.group(5)}")
        if value_type == "03":
            pending_path_id = resource_id
        elif value_type == "01":
            resource_values.setdefault(resource_id, []).append(("reference", value_data))
        else:
            resource_values.setdefault(resource_id, []).append(
                ("unsupported", f"type=0x{value_type} data={value_data}")
            )
        continue
    raise SystemExit(f"Unrecognized compiled XML resource-table line in {dump_path}: {line}")
if pending_path_id is not None:
    raise SystemExit(f"Missing backing file for compiled XML resource {pending_path_id} in {dump_path}")
if not resource_specs or not resource_values:
    raise SystemExit(f"Compiled XML resource table is empty in {dump_path}")
table_paths = {
    value
    for values in resource_values.values()
    for value_kind, value in values
    if value_kind == "path"
}
if table_paths != set(packaged_xml):
    raise SystemExit(
        f"Packaged XML sections do not exactly match compiled XML table paths in {dump_path}"
    )


def resolve_resource_paths(resource_id, resolving=()):
    if resource_id in resolving:
        raise SystemExit(f"Compiled XML resource alias cycle in {dump_path}: {resolving + (resource_id,)!r}")
    if resource_id not in resource_specs or resource_id not in resource_values:
        raise SystemExit(f"Referenced compiled XML resource is unresolved in {dump_path}: {resource_id}")
    package_name, _ = resource_specs[resource_id]
    if package_name != "com.securechat.app":
        raise SystemExit(
            f"Referenced XML resource {resource_id} belongs to unexpected package {package_name}"
        )
    resolved_paths = set()
    for value_kind, value in resource_values[resource_id]:
        if value_kind == "path":
            resolved_paths.add(value)
        elif value_kind == "reference":
            resolved_paths.update(resolve_resource_paths(value, resolving + (resource_id,)))
        else:
            raise SystemExit(f"Unsupported value for compiled XML resource {resource_id}: {value}")
    if not resolved_paths:
        raise SystemExit(f"Referenced compiled XML resource has no backing files: {resource_id}")
    for path in resolved_paths:
        if path not in packaged_xml or path not in sections:
            raise SystemExit(f"Backing file for compiled XML resource {resource_id} was not dumped: {path}")
    return resolved_paths


element_pattern = re.compile(r"^(\s*)E: ([^\s]+)(?:\s.*)?$")
attribute_pattern = re.compile(r"^(\s*)A: ([^=()\s]+)(?:\([^)]*\))?=(.*)$")
content_pattern = re.compile(r"^(\s*)C: (.*)$")


def parse_xmltree(resource_path):
    roots = []
    stack = []
    for line in sections[resource_path]:
        if not line or re.match(r"^\s*N: ", line):
            continue
        element_match = element_pattern.fullmatch(line)
        if element_match:
            indent = len(element_match.group(1))
            while stack and indent <= stack[-1]["indent"]:
                stack.pop()
            node = {
                "tag": element_match.group(2),
                "indent": indent,
                "attrs": {},
                "children": [],
                "content": [],
            }
            if stack:
                if indent != stack[-1]["indent"] + 2:
                    raise SystemExit(f"Invalid XML element indentation in {resource_path}: {line}")
                stack[-1]["children"].append(node)
            else:
                roots.append(node)
            stack.append(node)
            continue
        attribute_match = attribute_pattern.fullmatch(line)
        if attribute_match:
            if not stack or len(attribute_match.group(1)) != stack[-1]["indent"] + 2:
                raise SystemExit(f"Invalid XML attribute indentation in {resource_path}: {line}")
            attribute_name = attribute_match.group(2)
            if attribute_name in stack[-1]["attrs"]:
                raise SystemExit(f"Duplicate XML attribute {attribute_name} in {resource_path}")
            stack[-1]["attrs"][attribute_name] = attribute_match.group(3)
            continue
        content_match = content_pattern.fullmatch(line)
        if content_match:
            if not stack or len(content_match.group(1)) != stack[-1]["indent"] + 2:
                raise SystemExit(f"Invalid XML content indentation in {resource_path}: {line}")
            stack[-1]["content"].append(content_match.group(2))
            continue
        raise SystemExit(f"Unrecognized compiled XML line in {resource_path}: {line}")
    if not roots:
        raise SystemExit(f"Compiled XML resource is empty: {resource_path}")
    return roots


def walk(nodes):
    for node in nodes:
        yield node
        yield from walk(node["children"])


def literal_string(value):
    match = re.fullmatch(r'"([^"\\]*)" \(Raw: "([^"\\]*)"\)', value)
    if not match or match.group(1) != match.group(2):
        return None
    return match.group(1)


def resource_reference(value, description):
    match = re.fullmatch(r"@(0x[0-9a-fA-F]{8})", value)
    if not match:
        raise SystemExit(f"{description} must be a compiled resource reference; got {value!r}")
    return normalized_resource_id(match.group(1))

manifest_roots = parse_xmltree("AndroidManifest.xml")
if len(manifest_roots) != 1 or manifest_roots[0]["tag"] != "manifest":
    raise SystemExit("Packaged manifest must contain exactly one manifest root")
applications = [node for node in manifest_roots[0]["children"] if node["tag"] == "application"]
if len(applications) != 1:
    raise SystemExit("Packaged manifest must contain exactly one application")
application = applications[0]
if application["attrs"].get("android:allowBackup") != "(type 0x12)0x0":
    raise SystemExit("Application backup is not explicitly disabled in the packaged manifest")
if application["attrs"].get("android:usesCleartextTraffic") != "(type 0x12)0x0":
    raise SystemExit("Cleartext traffic is not explicitly disabled in the packaged manifest")
for forbidden_flag in ("android:debuggable", "android:testOnly"):
    forbidden_value = application["attrs"].get(forbidden_flag)
    if forbidden_value not in (None, "(type 0x12)0x0"):
        raise SystemExit(
            f"Forbidden or invalid packaged application flag {forbidden_flag}: {forbidden_value}"
        )

network_security_id = resource_reference(
    application["attrs"].get("android:networkSecurityConfig", ""),
    "android:networkSecurityConfig",
)

component_tags = {"activity", "activity-alias", "service", "receiver", "provider"}
component_nodes = [node for node in application["children"] if node["tag"] in component_tags]
components = []
for node in component_nodes:
    exported_value = node["attrs"].get("android:exported")
    if exported_value is None:
        exported = None
    elif exported_value == "(type 0x12)0x0":
        exported = False
    elif exported_value == "(type 0x12)0xffffffff":
        exported = True
    else:
        raise SystemExit(f"Invalid android:exported value in packaged manifest: {exported_value}")
    permission_value = node["attrs"].get("android:permission")
    components.append(
        {
            "tag": node["tag"],
            "name": literal_string(node["attrs"].get("android:name", "")),
            "permission": literal_string(permission_value) if permission_value is not None else None,
            "exported": exported,
            "node": node,
        }
    )
if not components:
    raise SystemExit("Packaged manifest contains no application components")
missing_exported = [
    (component["tag"], component["name"])
    for component in components
    if component["exported"] is None
]
if missing_exported:
    raise SystemExit(f"Every packaged component must declare android:exported explicitly: {missing_exported!r}")

exported_components = {
    (component["tag"], component.get("name"), component.get("permission"))
    for component in components
    if component["exported"]
}
expected_exported_components = {
    ("activity", "io.element.android.x.MainActivity", None),
    (
        "service",
        "androidx.work.impl.background.systemjob.SystemJobService",
        "android.permission.BIND_JOB_SERVICE",
    ),
}
if exported_components != expected_exported_components:
    raise SystemExit(
        "Unexpected packaged exported-component surface: "
        f"expected {sorted(expected_exported_components, key=repr)!r}, "
        f"got {sorted(exported_components, key=repr)!r}"
    )

file_provider_metadata_name = "android.support.FILE_PROVIDER_PATHS"
all_file_provider_metadata = [
    node
    for node in walk(manifest_roots)
    if (
        node["tag"] == "meta-data"
        and literal_string(node["attrs"].get("android:name", "")) == file_provider_metadata_name
    )
]

expected_file_providers = {
    "main FileProvider": {
        "name": "androidx.core.content.FileProvider",
        "authority": "com.securechat.app.fileprovider",
    },
    "notification FileProvider": {
        "name": "io.element.android.libraries.push.impl.notifications.NotificationsFileProvider",
        "authority": "com.securechat.app.notifications.fileprovider",
    },
}
file_provider_ids = {}
matched_metadata_nodes = set()
for role, expected_provider in expected_file_providers.items():
    provider_matches = [
        component
        for component in components
        if component["tag"] == "provider" and component["name"] == expected_provider["name"]
    ]
    if len(provider_matches) != 1:
        raise SystemExit(f"Packaged manifest must contain exactly one {role} declaration")
    component = provider_matches[0]
    provider_attrs = component["node"]["attrs"]
    if (
        set(provider_attrs)
        != {"android:name", "android:authorities", "android:exported", "android:grantUriPermissions"}
        or literal_string(provider_attrs["android:authorities"]) != expected_provider["authority"]
        or provider_attrs["android:exported"] != "(type 0x12)0x0"
        or provider_attrs["android:grantUriPermissions"] != "(type 0x12)0xffffffff"
    ):
        raise SystemExit(f"Unexpected packaged {role} declaration")
    provider_children = component["node"]["children"]
    if len(provider_children) != 1 or provider_children[0]["tag"] != "meta-data":
        raise SystemExit(f"Packaged {role} must contain exactly one paths metadata entry")
    metadata = provider_children[0]
    if literal_string(metadata["attrs"].get("android:name", "")) != file_provider_metadata_name:
        raise SystemExit(f"Packaged {role} has unexpected metadata")
    matched_metadata_nodes.add(id(metadata))
    if set(metadata["attrs"]) != {"android:name", "android:resource"}:
        raise SystemExit(f"Unexpected attributes on {role} paths metadata: {sorted(metadata['attrs'])}")
    file_provider_ids[role] = resource_reference(
        metadata["attrs"].get("android:resource", ""),
        f"{role} android:resource",
    )
if len(all_file_provider_metadata) != 2 or {
    id(metadata) for metadata in all_file_provider_metadata
} != matched_metadata_nodes:
    raise SystemExit("Packaged manifest must contain exactly the two expected FileProvider paths metadata entries")

referenced_ids = {"network security config": network_security_id, **file_provider_ids}
if len(set(referenced_ids.values())) != len(referenced_ids):
    raise SystemExit(f"Security-sensitive manifest XML references must be distinct: {referenced_ids!r}")
resolved_paths = {
    role: sorted(resolve_resource_paths(resource_id))
    for role, resource_id in referenced_ids.items()
}


def validate_network_security_config(resource_path):
    roots = parse_xmltree(resource_path)
    certificate_nodes = [node for node in walk(roots) if node["tag"] == "certificates"]
    certificate_sources = [node["attrs"].get("src") for node in certificate_nodes]
    expected_system_source = '"system" (Raw: "system")'
    if certificate_sources != [expected_system_source]:
        raise SystemExit(
            f"Packaged network security config {resource_path} must contain exactly one "
            f"certificates source, system; got {certificate_sources!r}"
        )
    if len(roots) != 1 or roots[0]["tag"] != "network-security-config":
        raise SystemExit(f"Unexpected packaged network-security root in {resource_path}")
    root = roots[0]
    if root["attrs"] or root["content"] or len(root["children"]) != 1:
        raise SystemExit(f"Packaged network security config must contain only one base policy: {resource_path}")
    base_config = root["children"][0]
    if (
        base_config["tag"] != "base-config"
        or base_config["attrs"] != {"cleartextTrafficPermitted": "(type 0x12)0x0"}
        or base_config["content"]
        or len(base_config["children"]) != 1
    ):
        raise SystemExit(f"Packaged network base policy is not TLS-only in {resource_path}")
    trust_anchors = base_config["children"][0]
    if (
        trust_anchors["tag"] != "trust-anchors"
        or trust_anchors["attrs"]
        or trust_anchors["content"]
        or len(trust_anchors["children"]) != 1
    ):
        raise SystemExit(f"Packaged network policy must contain only the system trust anchor: {resource_path}")
    certificates = trust_anchors["children"][0]
    if (
        certificates["tag"] != "certificates"
        or certificates["attrs"] != {"src": expected_system_source}
        or certificates["children"]
        or certificates["content"]
    ):
        raise SystemExit(f"Packaged network policy must trust only system CAs: {resource_path}")


for network_path in resolved_paths["network security config"]:
    validate_network_security_config(network_path)


def validate_provider_paths(resource_path, expected_entries, description):
    roots = parse_xmltree(resource_path)
    if len(roots) != 1 or roots[0]["tag"] != "paths":
        raise SystemExit(f"Unexpected packaged {description} root in {resource_path}")
    root = roots[0]
    if root["attrs"] or root["content"]:
        raise SystemExit(f"Unexpected packaged {description} root attributes in {resource_path}")
    actual_entries = []
    for node in root["children"]:
        if node["children"] or node["content"]:
            raise SystemExit(f"Unexpected nested packaged {description} entry in {resource_path}")
        actual_entries.append(
            {
                "tag": node["tag"],
                "name": literal_string(node["attrs"].get("name", "")),
                "path": literal_string(node["attrs"].get("path", "")),
                "attribute_names": sorted(node["attrs"]),
            }
        )
    expected_with_attributes = [
        {**entry, "attribute_names": ["name", "path"]}
        for entry in expected_entries
    ]
    if actual_entries != expected_with_attributes:
        raise SystemExit(f"Unexpected packaged {description}: {actual_entries!r}")


expected_main_paths = [
    {"tag": "cache-path", "name": "camera_capture", "path": "temp/camera/"},
    {"tag": "cache-path", "name": "media", "path": "temp/media/"},
    {"tag": "cache-path", "name": "outgoing", "path": "temp/outgoing/"},
    {"tag": "files-path", "name": "notification_sounds", "path": "notification_sounds/"},
]
expected_notification_paths = [
    {"tag": "cache-path", "name": "downloads", "path": "temp/notif/"},
]
for provider_path in resolved_paths["main FileProvider"]:
    validate_provider_paths(provider_path, expected_main_paths, "main FileProvider paths")
for provider_path in resolved_paths["notification FileProvider"]:
    validate_provider_paths(
        provider_path,
        expected_notification_paths,
        "notification FileProvider paths",
    )
PY
