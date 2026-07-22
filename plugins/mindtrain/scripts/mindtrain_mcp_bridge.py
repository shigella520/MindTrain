#!/usr/bin/env python3
"""Local MCP bridge for configuring and accessing a private MindTrain instance."""

import argparse
import datetime
import getpass
import hashlib
import json
import os
import re
import secrets
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import venv
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse, urlunparse
from urllib.request import Request, urlopen


CONFIGURE_TOOL = "configure_mindtrain_instance"
STATUS_TOOL = "get_mindtrain_configuration"
REFERENCE_TOOL_NAMES = {
    "configure_reference_library",
    "list_reference_libraries",
    "sync_reference_library",
    "get_reference_library_status",
    "list_reference_documents",
    "search_reference_library",
    "read_reference_document",
    "remove_reference_library",
}
SUPPORTED_REFERENCE_EXTENSIONS = {".md", ".txt", ".pdf", ".docx", ".pptx"}
REFERENCE_LIBRARY_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
REMOTE_TOOL_NAMES = {
    "create_training_session",
    "get_next_assignment",
    "submit_choice_answer",
    "reject_generated_question",
    "record_interaction",
    "create_candidate_question",
    "revise_saved_question",
    "finish_training_session",
    "get_learning_report",
    "get_scheduler_backlog",
    "list_knowledge_domains",
    "get_knowledge_catalog_tree",
    "search_knowledge_topics",
    "get_knowledge_topic",
    "preview_training_domain",
    "get_training_domain_draft",
    "confirm_training_domain",
    "discard_training_domain_draft",
    "preview_knowledge_catalog_import",
    "get_knowledge_catalog_import",
    "apply_knowledge_catalog_import",
    "reject_knowledge_catalog_import",
}
CONTRACT_VERSION = 1


def plugin_version():
    manifest = Path(__file__).resolve().parents[1] / ".codex-plugin" / "plugin.json"
    try:
        return json.loads(manifest.read_text(encoding="utf-8"))["version"]
    except (OSError, KeyError, TypeError, json.JSONDecodeError):
        return "0.0.0"


PLUGIN_VERSION = plugin_version()


def normalized_version(value):
    match = re.match(r"^(\d+)\.(\d+)\.(\d+)", value or "")
    if not match:
        return None
    return tuple(int(part) for part in match.groups())


def string_property(description):
    return {"type": "string", "description": description}


def integer_property(description):
    return {"type": "integer", "description": description}


def object_property(description):
    return {"type": "object", "description": description}


def schema(properties, required=()):
    return {
        "type": "object",
        "properties": properties,
        "required": list(required),
        "additionalProperties": False,
    }


def tool(name, description, input_schema):
    return {"name": name, "description": description, "inputSchema": input_schema}


def tool_definitions():
    definitions = [
        tool(
            STATUS_TOOL,
            "Check whether this Codex installation is connected to a private MindTrain instance.",
            schema({}),
        ),
        tool(
            CONFIGURE_TOOL,
            "Validate and save the private MindTrain MCP URL and single-user access token locally.",
            schema(
                {
                    "url": string_property("Full private Trainer MCP URL, for example https://train.example.com/mcp"),
                    "token": string_property("MindTrain single-user Bootstrap Token"),
                    "allowInsecureHttp": {
                        "type": "boolean",
                        "description": "Allow plain HTTP for a trusted private network; defaults to false",
                    },
                },
                ("url", "token"),
            ),
        ),
        tool(
            "create_training_session",
            "Create a MindTrain training session.",
            schema(
                {
                    "domainId": string_property("Optional training domain ID; required when multiple domains exist"),
                    "schedulerProvider": string_property("Scheduler provider ID; use weighted for 加权调度 in the MVP"),
                }
            ),
        ),
        tool(
            "get_next_assignment",
            "Get the next safe-to-display question or a structured generationProfile.",
            schema({"sessionId": string_property("Active session ID")}, ("sessionId",)),
        ),
        tool(
            "submit_choice_answer",
            "Submit a formal choice answer and receive deterministic grading.",
            schema(
                {
                    "assignmentId": string_property("Pending assignment ID"),
                    "answer": string_property("User answer text"),
                },
                ("assignmentId", "answer"),
            ),
        ),
        tool(
            "reject_generated_question",
            "Reject an unanswered AI-generated question, physically delete it, restore its new-item allowance, and request a replacement next.",
            schema(
                {"assignmentId": string_property("Pending generated assignment ID")},
                ("assignmentId",),
            ),
        ),
        tool(
            "record_interaction",
            "Record a clarification, hint request, challenge or follow-up without consuming the question.",
            schema(
                {
                    "sessionId": string_property("Active session ID"),
                    "assignmentId": string_property("Related assignment ID"),
                    "eventType": string_property("Interaction event type"),
                    "content": string_property("Interaction text"),
                    "model": string_property("Model identifier when known"),
                    "promptVersion": string_property("Prompt version when known"),
                },
                ("sessionId", "content"),
            ),
        ),
        tool(
            "create_candidate_question",
            "Validate and save a candidate matching the issued generationProfile for its owning session only.",
            schema(
                {
                    "sessionId": string_property("Owning active session ID"),
                    "topicId": string_property("Requested topic ID"),
                    "question": object_property("Complete candidate question object"),
                    "attemptType": string_property("Use follow_up only for a deeper training question"),
                    "parentAttemptId": string_property("Required when attemptType is follow_up"),
                },
                ("sessionId", "topicId", "question"),
            ),
        ),
        tool(
            "revise_saved_question",
            "Create an immutable next version of an active saved question after explicit user approval.",
            schema(
                {
                    "questionId": string_property("Active saved question ID"),
                    "expectedVersion": integer_property("Version shown in the assignment; prevents stale overwrites"),
                    "changes": object_property("Only changed question fields"),
                    "reason": string_property("Concise reason for the revision audit log"),
                    "sourceAssignmentId": string_property("Assignment that exposed the issue when available"),
                    "model": string_property("Model identifier when known"),
                    "promptVersion": string_property("Prompt version when known"),
                },
                ("questionId", "expectedVersion", "changes", "reason"),
            ),
        ),
        tool(
            "finish_training_session",
            "Finish a session and persist its summary.",
            schema({"sessionId": string_property("Session ID")}, ("sessionId",)),
        ),
        tool("get_learning_report", "Get learning, backlog and content overview metrics.", schema({})),
        tool(
            "get_scheduler_backlog",
            "Get due backlog and current new-item allowance.",
            schema({"domainId": string_property("Optional training domain filter")}),
        ),
        tool(
            "list_knowledge_domains",
            "List the authenticated user's training domains and catalog coverage.",
            schema({"query": string_property("Optional domain name or description filter")}),
        ),
        tool(
            "get_knowledge_catalog_tree",
            "Get all root knowledge points and descendants for one domain.",
            schema({"domainId": string_property("Knowledge domain ID")}, ("domainId",)),
        ),
        tool(
            "search_knowledge_topics",
            "Search knowledge points and return their domain and ancestor path.",
            schema(
                {
                    "query": string_property("Topic name, description or keyword query"),
                    "domainId": string_property("Optional domain filter"),
                    "limit": integer_property("Optional page size from 1 to 100"),
                    "cursor": string_property("Optional cursor returned by a prior search"),
                },
                ("query",),
            ),
        ),
        tool(
            "get_knowledge_topic",
            "Get one knowledge point with children, relations, sources and coverage.",
            schema({"topicId": string_property("Knowledge point ID")}, ("topicId",)),
        ),
        tool(
            "preview_training_domain",
            "Validate and save an immutable training-domain draft from local references or AI dialogue.",
            schema(
                {
                    "originType": string_property("local_reference or ai_dialogue"),
                    "libraryId": string_property("Required only for local_reference; never send an absolute path"),
                    "context": object_property("Learning goal, audience, scope, model and prompt version"),
                    "proposal": object_property("Exactly one domain target with topics, relations and sources"),
                },
                ("originType", "proposal"),
            ),
        ),
        tool(
            "get_training_domain_draft",
            "Get a training-domain draft, validation, warnings and persistence diff.",
            schema({"draftId": string_property("Training-domain draft ID")}, ("draftId",)),
        ),
        tool(
            "confirm_training_domain",
            "Save and enable a complete draft after explicit user confirmation.",
            schema(
                {
                    "draftId": string_property("Training-domain draft ID"),
                    "proposalHash": string_property("Exact hash returned by preview"),
                },
                ("draftId", "proposalHash"),
            ),
        ),
        tool(
            "discard_training_domain_draft",
            "Discard a draft without changing active catalog data.",
            schema({"draftId": string_property("Training-domain draft ID")}, ("draftId",)),
        ),
        tool(
            "preview_knowledge_catalog_import",
            "Compatibility alias for preview_training_domain for local-reference clients.",
            schema(
                {
                    "libraryId": string_property("Local reference library ID; no absolute path"),
                    "proposal": object_property("Domains, topics, relations and source metadata proposal"),
                },
                ("libraryId", "proposal"),
            ),
        ),
        tool(
            "get_knowledge_catalog_import",
            "Get a training-domain preview, validation result and persistence diff.",
            schema({"importId": string_property("Catalog import ID")}, ("importId",)),
        ),
        tool(
            "apply_knowledge_catalog_import",
            "Save and enable a previewed training domain after explicit user confirmation.",
            schema(
                {
                    "importId": string_property("Catalog import ID"),
                    "proposalHash": string_property("Exact hash returned by preview"),
                },
                ("importId", "proposalHash"),
            ),
        ),
        tool(
            "reject_knowledge_catalog_import",
            "Discard a training-domain proposal without changing active data.",
            schema({"importId": string_property("Catalog import ID")}, ("importId",)),
        ),
    ]
    definitions.extend(
        [
            tool(
                "configure_reference_library",
                "Name and save a local reference directory, then prepare its private document parser runtime.",
                schema(
                    {
                        "libraryId": string_property("Stable lowercase ID, using letters, digits, hyphens or underscores"),
                        "path": string_property("Absolute path to the local reference directory"),
                        "sourcePolicy": string_property("Source fallback policy; currently ask"),
                        "makeDefault": {"type": "boolean", "description": "Make this the default reference library"},
                    },
                    ("libraryId", "path"),
                ),
            ),
            tool("list_reference_libraries", "List locally configured reference libraries without exposing absolute paths.", schema({})),
            tool(
                "sync_reference_library",
                "Incrementally extract and index supported files in a local reference library.",
                schema({"libraryId": string_property("Configured reference library ID")}, ("libraryId",)),
            ),
            tool(
                "get_reference_library_status",
                "Get local indexing status and warnings for a reference library.",
                schema({"libraryId": string_property("Configured reference library ID")}, ("libraryId",)),
            ),
            tool(
                "list_reference_documents",
                "List indexed document metadata; original text and absolute paths remain local.",
                schema(
                    {
                        "libraryId": string_property("Configured reference library ID"),
                        "limit": integer_property("Maximum number of documents; defaults to 100"),
                    },
                    ("libraryId",),
                ),
            ),
            tool(
                "search_reference_library",
                "Search the private local full-text index and return short evidence snippets.",
                schema(
                    {
                        "libraryId": string_property("Configured reference library ID"),
                        "query": string_property("Search terms"),
                        "limit": integer_property("Maximum matches; defaults to 5"),
                    },
                    ("libraryId", "query"),
                ),
            ),
            tool(
                "read_reference_document",
                "Read a bounded local excerpt by relative path for grounded question generation.",
                schema(
                    {
                        "libraryId": string_property("Configured reference library ID"),
                        "relativePath": string_property("Indexed POSIX-style relative path"),
                        "offset": integer_property("Character offset; defaults to 0"),
                        "maxCharacters": integer_property("Maximum excerpt length; defaults to 6000 and is capped at 20000"),
                    },
                    ("libraryId", "relativePath"),
                ),
            ),
            tool(
                "remove_reference_library",
                "Remove a local reference library configuration and its rebuildable index only.",
                schema({"libraryId": string_property("Configured reference library ID")}, ("libraryId",)),
            ),
        ]
    )
    return definitions


def config_path():
    configured = os.environ.get("MINDTRAIN_PLUGIN_CONFIG")
    if configured:
        return Path(configured).expanduser()
    if os.name == "nt" and os.environ.get("APPDATA"):
        return Path(os.environ["APPDATA"]) / "MindTrain" / "plugin.json"
    root = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    return root / "mindtrain" / "plugin.json"


def cache_root():
    configured = os.environ.get("MINDTRAIN_PLUGIN_CACHE")
    if configured:
        return Path(configured).expanduser()
    if os.name == "nt" and os.environ.get("LOCALAPPDATA"):
        return Path(os.environ["LOCALAPPDATA"]) / "MindTrain"
    root = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache"))
    return root / "mindtrain"


def load_raw_config(path=None):
    path = path or config_path()
    if not path.exists():
        return {"schemaVersion": 2, "referenceLibraries": []}
    data = json.loads(path.read_text(encoding="utf-8"))
    data.setdefault("schemaVersion", 2)
    data.setdefault("referenceLibraries", [])
    return data


def normalize_url(value, allow_insecure_http=False):
    parsed = urlparse(value.strip())
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ValueError("MindTrain URL must be an absolute HTTP(S) URL")
    hostname = (parsed.hostname or "").lower()
    local_host = hostname in {"localhost", "127.0.0.1", "::1"}
    if parsed.scheme != "https" and not local_host and not allow_insecure_http:
        raise ValueError("HTTPS is required unless allowInsecureHttp is explicitly enabled")
    path = parsed.path.rstrip("/")
    if not path:
        path = "/mcp"
    return urlunparse((parsed.scheme, parsed.netloc, path, "", parsed.query, ""))


def load_config(path=None):
    path = path or config_path()
    if not path.exists():
        return None
    data = load_raw_config(path)
    if not data.get("url") or not data.get("token"):
        raise ValueError("MindTrain plugin configuration is incomplete")
    return data


def save_raw_config(data, path=None):
    path = path or config_path()
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    try:
        path.parent.chmod(0o700)
    except OSError:
        pass
    payload = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    descriptor, temporary_name = tempfile.mkstemp(prefix="plugin-", suffix=".json", dir=path.parent)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(payload)
        os.replace(temporary_name, path)
        try:
            path.chmod(0o600)
        except OSError:
            pass
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)
    return path


def save_config(url, token, path=None):
    data = load_raw_config(path)
    data.update({"schemaVersion": 2, "url": url, "token": token})
    return save_raw_config(data, path)


def validate_library_id(value):
    library_id = str(value or "").strip()
    if not REFERENCE_LIBRARY_ID_PATTERN.fullmatch(library_id):
        raise ValueError("libraryId must be 1-64 lowercase letters, digits, hyphens or underscores")
    return library_id


def reference_library(library_id):
    library_id = validate_library_id(library_id)
    for library in load_raw_config().get("referenceLibraries", []):
        if library.get("id") == library_id:
            root = Path(library["path"]).expanduser().resolve(strict=True)
            if not root.is_dir():
                raise ValueError("Reference library path is not a directory")
            return library, root
    raise ValueError("Unknown reference library: " + library_id)


def reference_index_path(library_id):
    return cache_root() / "reference-libraries" / validate_library_id(library_id) / "index.sqlite"


def requirements_lock_path():
    return Path(__file__).with_name("reference-requirements.lock")


def reference_worker_path():
    return Path(__file__).with_name("reference_worker.py")


def reference_runtime_python():
    lock = requirements_lock_path().read_bytes()
    runtime = cache_root() / "reference-runtime" / hashlib.sha256(lock).hexdigest()[:16] / "venv"
    python = runtime / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    marker = runtime / ".mindtrain-ready"
    if marker.exists() and python.exists():
        return python
    runtime.parent.mkdir(parents=True, exist_ok=True)
    temporary = runtime.parent / (runtime.name + ".tmp-" + secrets.token_hex(4))
    try:
        venv.EnvBuilder(with_pip=True, clear=True).create(temporary)
        temporary_python = temporary / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
        subprocess.run(
            [str(temporary_python), "-m", "pip", "install", "--disable-pip-version-check", "-r", str(requirements_lock_path())],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        (temporary / ".mindtrain-ready").write_text("ready\n", encoding="utf-8")
        if runtime.exists():
            shutil.rmtree(runtime)
        os.replace(temporary, runtime)
    except subprocess.CalledProcessError as error:
        raise ValueError("Unable to install local reference parser dependencies: " + error.stderr.strip()) from error
    finally:
        if temporary.exists():
            shutil.rmtree(temporary, ignore_errors=True)
    return python


def configure_reference_library(arguments):
    library_id = validate_library_id(arguments.get("libraryId"))
    raw_path = Path(str(arguments.get("path") or "")).expanduser()
    if not raw_path.is_absolute():
        raise ValueError("Reference library path must be absolute")
    root = raw_path.resolve(strict=True)
    if not root.is_dir():
        raise ValueError("Reference library path must be a directory")
    policy = str(arguments.get("sourcePolicy") or "ask")
    if policy != "ask":
        raise ValueError("sourcePolicy must be ask")

    # Prepare the isolated parser before changing the durable configuration.
    reference_runtime_python()
    data = load_raw_config()
    libraries = data.setdefault("referenceLibraries", [])
    previous = next((item for item in libraries if item.get("id") == library_id), None)
    if previous and Path(previous.get("path", "")).expanduser() != root:
        shutil.rmtree(reference_index_path(library_id).parent, ignore_errors=True)
    entry = {"id": library_id, "path": str(root), "sourcePolicy": policy}
    libraries[:] = [item for item in libraries if item.get("id") != library_id]
    libraries.append(entry)
    if arguments.get("makeDefault", False) or not data.get("defaultReferenceLibraryId"):
        data["defaultReferenceLibraryId"] = library_id
    save_raw_config(data)
    return {"configured": True, "libraryId": library_id, "sourcePolicy": policy, "isDefault": data.get("defaultReferenceLibraryId") == library_id}


def connect_reference_index(library_id):
    path = reference_index_path(library_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path)
    connection.row_factory = sqlite3.Row
    connection.executescript(
        """
        PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS documents (
          id INTEGER PRIMARY KEY,
          relative_path TEXT NOT NULL UNIQUE,
          title TEXT NOT NULL,
          extension TEXT NOT NULL,
          byte_size INTEGER NOT NULL,
          modified_ns INTEGER NOT NULL,
          content_hash TEXT NOT NULL,
          indexed_at TEXT NOT NULL,
          text_content TEXT NOT NULL,
          warning TEXT
        );
        CREATE VIRTUAL TABLE IF NOT EXISTS documents_fts USING fts5(
          title, relative_path, text_content, tokenize='unicode61'
        );
        CREATE TABLE IF NOT EXISTS library_state (
          key TEXT PRIMARY KEY,
          value TEXT NOT NULL
        );
        """
    )
    return connection


def safe_reference_files(root):
    for directory, directory_names, file_names in os.walk(root, followlinks=False):
        directory_names[:] = sorted(name for name in directory_names if not name.startswith("."))
        for file_name in sorted(file_names):
            if file_name.startswith(".") or file_name.startswith("~$"):
                continue
            path = Path(directory) / file_name
            if path.suffix.lower() not in SUPPORTED_REFERENCE_EXTENSIONS:
                continue
            try:
                resolved = path.resolve(strict=True)
                resolved.relative_to(root)
            except (OSError, ValueError):
                continue
            if resolved.is_file():
                yield path, resolved


def extract_reference_document(path):
    extension = path.suffix.lower()
    if extension in {".md", ".txt"}:
        text = path.read_text(encoding="utf-8", errors="replace")
        title = next((line.lstrip("# ").strip() for line in text.splitlines() if line.strip()), path.stem)
        return {"title": title[:500], "text": text, "warning": None}
    process = subprocess.run(
        [str(reference_runtime_python()), str(reference_worker_path()), str(path)],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if process.returncode != 0:
        message = process.stderr.strip() or "document parser failed"
        return {"title": path.stem, "text": "", "warning": message[:1000]}
    try:
        result = json.loads(process.stdout)
    except json.JSONDecodeError:
        return {"title": path.stem, "text": "", "warning": "document parser returned invalid output"}
    return {"title": str(result.get("title") or path.stem)[:500], "text": str(result.get("text") or ""), "warning": result.get("warning")}


def sync_reference_library(library_id):
    library, root = reference_library(library_id)
    connection = connect_reference_index(library["id"])
    discovered = set()
    added = updated = unchanged = warnings = 0
    now = datetime.datetime.now(datetime.timezone.utc).isoformat()
    try:
        for path, resolved in safe_reference_files(root):
            relative = path.relative_to(root).as_posix()
            discovered.add(relative)
            stat_result = resolved.stat()
            content_hash = hashlib.sha256(resolved.read_bytes()).hexdigest()
            existing = connection.execute("SELECT * FROM documents WHERE relative_path = ?", (relative,)).fetchone()
            if existing and existing["content_hash"] == content_hash:
                unchanged += 1
                if existing["byte_size"] != stat_result.st_size or existing["modified_ns"] != stat_result.st_mtime_ns:
                    connection.execute("UPDATE documents SET byte_size = ?, modified_ns = ? WHERE id = ?", (stat_result.st_size, stat_result.st_mtime_ns, existing["id"]))
                continue
            extracted = extract_reference_document(resolved)
            warning = extracted.get("warning")
            warnings += int(bool(warning))
            if existing:
                document_id = existing["id"]
                connection.execute(
                    "UPDATE documents SET title=?, extension=?, byte_size=?, modified_ns=?, content_hash=?, indexed_at=?, text_content=?, warning=? WHERE id=?",
                    (extracted["title"], path.suffix.lower(), stat_result.st_size, stat_result.st_mtime_ns, content_hash, now, extracted["text"], warning, document_id),
                )
                connection.execute("DELETE FROM documents_fts WHERE rowid = ?", (document_id,))
                updated += 1
            else:
                cursor = connection.execute(
                    "INSERT INTO documents(relative_path,title,extension,byte_size,modified_ns,content_hash,indexed_at,text_content,warning) VALUES(?,?,?,?,?,?,?,?,?)",
                    (relative, extracted["title"], path.suffix.lower(), stat_result.st_size, stat_result.st_mtime_ns, content_hash, now, extracted["text"], warning),
                )
                document_id = cursor.lastrowid
                added += 1
            connection.execute("INSERT INTO documents_fts(rowid,title,relative_path,text_content) VALUES(?,?,?,?)", (document_id, extracted["title"], relative, extracted["text"]))
        removed_rows = connection.execute("SELECT id, relative_path FROM documents").fetchall()
        removed = 0
        for row in removed_rows:
            if row["relative_path"] not in discovered:
                connection.execute("DELETE FROM documents_fts WHERE rowid = ?", (row["id"],))
                connection.execute("DELETE FROM documents WHERE id = ?", (row["id"],))
                removed += 1
        connection.execute("INSERT OR REPLACE INTO library_state(key,value) VALUES('lastSyncAt',?)", (now,))
        connection.commit()
        total = connection.execute("SELECT COUNT(*) FROM documents").fetchone()[0]
    finally:
        connection.close()
    return {"libraryId": library["id"], "added": added, "updated": updated, "unchanged": unchanged, "removed": removed, "warnings": warnings, "documentCount": total, "syncedAt": now}


def source_metadata(library_id, row):
    source_key = "{}:{}:{}".format(library_id, row["relative_path"], row["content_hash"])
    return {
        "sourceType": "local_reference",
        "sourceId": "local-" + hashlib.sha256(source_key.encode("utf-8")).hexdigest(),
        "url": "mindtrain-local://{}/{}".format(library_id, row["relative_path"]),
        "libraryId": library_id,
        "relativePath": row["relative_path"],
        "contentHash": row["content_hash"],
        "title": row["title"],
        "accessedAt": row["indexed_at"][:10],
    }


def bounded_int(value, default, minimum, maximum):
    try:
        parsed = int(value if value is not None else default)
    except (TypeError, ValueError) as error:
        raise ValueError("Expected an integer") from error
    return max(minimum, min(maximum, parsed))


def reference_tool_call(name, arguments):
    if name == "configure_reference_library":
        return configure_reference_library(arguments)
    if name == "list_reference_libraries":
        data = load_raw_config()
        default = data.get("defaultReferenceLibraryId")
        return {"defaultReferenceLibraryId": default, "libraries": [{"libraryId": item.get("id"), "sourcePolicy": item.get("sourcePolicy", "ask"), "isDefault": item.get("id") == default} for item in data.get("referenceLibraries", [])]}
    library_id = validate_library_id(arguments.get("libraryId"))
    if name == "sync_reference_library":
        return sync_reference_library(library_id)
    if name == "remove_reference_library":
        data = load_raw_config()
        before = len(data.get("referenceLibraries", []))
        data["referenceLibraries"] = [item for item in data.get("referenceLibraries", []) if item.get("id") != library_id]
        if len(data["referenceLibraries"]) == before:
            raise ValueError("Unknown reference library: " + library_id)
        if data.get("defaultReferenceLibraryId") == library_id:
            data["defaultReferenceLibraryId"] = data["referenceLibraries"][0]["id"] if data["referenceLibraries"] else None
        save_raw_config(data)
        shutil.rmtree(reference_index_path(library_id).parent, ignore_errors=True)
        return {"removed": True, "libraryId": library_id}
    reference_library(library_id)
    connection = connect_reference_index(library_id)
    try:
        if name == "get_reference_library_status":
            count = connection.execute("SELECT COUNT(*) FROM documents").fetchone()[0]
            warning_rows = connection.execute("SELECT relative_path, warning FROM documents WHERE warning IS NOT NULL ORDER BY relative_path LIMIT 100").fetchall()
            state = connection.execute("SELECT value FROM library_state WHERE key='lastSyncAt'").fetchone()
            return {"libraryId": library_id, "documentCount": count, "lastSyncAt": state[0] if state else None, "warnings": [dict(row) for row in warning_rows]}
        if name == "list_reference_documents":
            limit = bounded_int(arguments.get("limit"), 100, 1, 500)
            rows = connection.execute("SELECT * FROM documents ORDER BY relative_path LIMIT ?", (limit,)).fetchall()
            return {"libraryId": library_id, "documents": [{**source_metadata(library_id, row), "extension": row["extension"], "byteSize": row["byte_size"], "warning": row["warning"]} for row in rows]}
        if name == "search_reference_library":
            query = str(arguments.get("query") or "").strip()
            if not query:
                raise ValueError("query must not be empty")
            limit = bounded_int(arguments.get("limit"), 5, 1, 20)
            quoted = '"' + query.replace('"', '""') + '"'
            rows = connection.execute(
                "SELECT d.*, snippet(documents_fts,2,'[',']',' … ',24) AS snippet FROM documents_fts JOIN documents d ON d.id=documents_fts.rowid WHERE documents_fts MATCH ? LIMIT ?",
                (quoted, limit),
            ).fetchall()
            if not rows:
                rows = connection.execute("SELECT *, substr(text_content,1,800) AS snippet FROM documents WHERE text_content LIKE ? OR title LIKE ? ORDER BY relative_path LIMIT ?", ("%" + query + "%", "%" + query + "%", limit)).fetchall()
            return {"libraryId": library_id, "matches": [{**source_metadata(library_id, row), "locator": "search", "snippet": row["snippet"]} for row in rows]}
        if name == "read_reference_document":
            relative = str(arguments.get("relativePath") or "")
            if not relative or relative.startswith(("/", "\\")) or "\\" in relative or ".." in Path(relative).parts:
                raise ValueError("relativePath must be a safe indexed POSIX path")
            row = connection.execute("SELECT * FROM documents WHERE relative_path = ?", (relative,)).fetchone()
            if row is None:
                raise ValueError("Document is not indexed")
            offset = bounded_int(arguments.get("offset"), 0, 0, len(row["text_content"]))
            maximum = bounded_int(arguments.get("maxCharacters"), 6000, 1, 20000)
            excerpt = row["text_content"][offset:offset + maximum]
            return {**source_metadata(library_id, row), "locator": "characters:{}-{}".format(offset, offset + len(excerpt)), "content": excerpt, "truncated": offset + len(excerpt) < len(row["text_content"])}
    finally:
        connection.close()
    raise ValueError("Unknown reference tool: " + name)


def remote_request(config, method, params=None, timeout=15):
    request_id = secrets.randbelow(2**31 - 1) + 1
    payload = json.dumps(
        {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params or {}}
    ).encode("utf-8")
    request = Request(
        config["url"],
        data=payload,
        method="POST",
        headers={
            "Authorization": "Bearer " + config["token"],
            "Content-Type": "application/json",
            "Accept": "application/json",
            "X-MindTrain-Plugin-Version": PLUGIN_VERSION,
            "X-MindTrain-Contract-Version": str(CONTRACT_VERSION),
        },
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            body = response.read()
    except HTTPError as error:
        if error.code == 401:
            raise ValueError("MindTrain rejected the access token") from error
        raise ValueError("MindTrain returned HTTP {}".format(error.code)) from error
    except URLError as error:
        raise ValueError("Unable to reach MindTrain: {}".format(error.reason)) from error
    try:
        result = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("MindTrain returned an invalid JSON response") from error
    if result.get("error"):
        raise ValueError(result["error"].get("message", "MindTrain returned an MCP error"))
    return result.get("result", {})


def verify_config(config):
    result = remote_request(
        config,
        "initialize",
        {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "mindtrain-plugin", "version": PLUGIN_VERSION}},
    )
    server_info = result.get("serverInfo", {})
    if server_info.get("name") != "mindtrain-trainer-mcp":
        raise ValueError("The configured URL is not a MindTrain Trainer MCP server")
    metadata = result.get("_meta", {}).get("mindtrainCompatibility")
    if not isinstance(metadata, dict):
        raise ValueError(
            "MindTrain 服务端未提供版本兼容信息。请升级服务端后重试；不要继续使用可能不兼容的 Plugin。"
        )
    server_contract = metadata.get("contractVersion")
    if server_contract != CONTRACT_VERSION:
        raise ValueError(
            "MindTrain Plugin 契约版本 {} 与服务端契约版本 {} 不兼容。请同步升级 Plugin 与服务端，并开启新任务。"
            .format(CONTRACT_VERSION, server_contract)
        )
    minimum_plugin = str(metadata.get("minimumPluginVersion", ""))
    current = normalized_version(PLUGIN_VERSION)
    minimum = normalized_version(minimum_plugin)
    if current is None or minimum is None or current < minimum:
        raise ValueError(
            "MindTrain Plugin 版本 {} 低于服务端要求的最低版本 {}。请更新或重新安装 Plugin，并开启新任务。"
            .format(PLUGIN_VERSION, minimum_plugin or "unknown")
        )
    server_version = str(server_info.get("version", "unknown"))
    version_match = normalized_version(server_version) == current
    compatibility = {
        "status": "compatible" if version_match else "compatible_version_difference",
        "pluginVersion": PLUGIN_VERSION,
        "serverVersion": server_version,
        "contractVersion": CONTRACT_VERSION,
        "minimumPluginVersion": minimum_plugin,
        "versionMatch": version_match,
    }
    if not version_match:
        compatibility["warning"] = (
            "Plugin 与服务端组件版本不同，但当前契约兼容。建议同步升级，避免后续版本失配。"
        )
    return compatibility


def content_result(value, is_error=False):
    return {
        "content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False)}],
        "structuredContent": value,
        "isError": is_error,
    }


def configuration_status():
    path = config_path()
    try:
        config = load_config(path)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        return {"configured": False, "configPath": str(path), "error": str(error)}
    if config is None:
        return {"configured": False, "configPath": str(path)}
    result = {"configured": True, "url": config["url"], "configPath": str(path)}
    try:
        result["compatibility"] = verify_config(config)
    except ValueError as error:
        result["compatibility"] = {"status": "incompatible_or_unavailable", "error": str(error)}
    return result


def call_tool(name, arguments):
    if name == STATUS_TOOL:
        return content_result(configuration_status())
    if name == CONFIGURE_TOOL:
        try:
            url = normalize_url(arguments.get("url", ""), bool(arguments.get("allowInsecureHttp", False)))
            token = arguments.get("token", "")
            if not token or token.isspace() or "\n" in token or "\r" in token:
                raise ValueError("A non-empty single-line MindTrain token is required")
            candidate = {"url": url, "token": token}
            compatibility = verify_config(candidate)
            path = save_config(url, token)
            return content_result({"configured": True, "url": url, "configPath": str(path),
                                   "compatibility": compatibility})
        except (OSError, ValueError) as error:
            return content_result({"configured": False, "error": str(error)}, True)
    if name in REFERENCE_TOOL_NAMES:
        try:
            return content_result(reference_tool_call(name, arguments))
        except (OSError, ValueError, sqlite3.Error, json.JSONDecodeError) as error:
            return content_result({"error": str(error)}, True)
    if name not in REMOTE_TOOL_NAMES:
        return content_result({"error": "Unknown tool: " + name}, True)
    try:
        config = load_config()
        if config is None:
            return content_result(
                {
                    "status": "configuration_required",
                    "message": "Call configure_mindtrain_instance before starting training.",
                },
                True,
            )
        verify_config(config)
        return remote_request(config, "tools/call", {"name": name, "arguments": arguments})
    except (OSError, ValueError, json.JSONDecodeError) as error:
        return content_result({"error": str(error)}, True)


def handle_request(request):
    method = request.get("method", "")
    if method == "initialize":
        return {
            "protocolVersion": request.get("params", {}).get("protocolVersion", "2025-03-26"),
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "mindtrain-plugin-bridge", "version": PLUGIN_VERSION},
            "instructions": "Check MindTrain configuration before training. Never expose the saved token.",
        }
    if method == "ping":
        return {}
    if method == "tools/list":
        return {"tools": tool_definitions()}
    if method == "tools/call":
        params = request.get("params", {})
        return call_tool(params.get("name", ""), params.get("arguments", {}))
    raise ValueError("Method not found: " + method)


def run_stdio_server():
    for line in sys.stdin:
        request = None
        try:
            request = json.loads(line)
            if "id" not in request:
                continue
            response = {"jsonrpc": "2.0", "id": request["id"], "result": handle_request(request)}
        except (ValueError, json.JSONDecodeError) as error:
            response = {
                "jsonrpc": "2.0",
                "id": request.get("id") if isinstance(request, dict) else None,
                "error": {"code": -32603, "message": str(error)},
            }
        sys.stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
        sys.stdout.flush()


def configure_interactively():
    url = input("MindTrain MCP URL: ").strip()
    token = getpass.getpass("MindTrain Bootstrap Token: ")
    normalized = normalize_url(url)
    candidate = {"url": normalized, "token": token}
    verify_config(candidate)
    path = save_config(normalized, token)
    print("MindTrain configured at {} ({})".format(normalized, path))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--configure", action="store_true", help="Configure the private instance interactively")
    args = parser.parse_args()
    if args.configure:
        configure_interactively()
    else:
        run_stdio_server()


if __name__ == "__main__":
    main()
