#!/usr/bin/env python3
"""Local MCP bridge for configuring and accessing a private MindTrain instance."""

import argparse
import getpass
import json
import os
import secrets
import sys
import tempfile
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse, urlunparse
from urllib.request import Request, urlopen


CONFIGURE_TOOL = "configure_mindtrain_instance"
STATUS_TOOL = "get_mindtrain_configuration"
REMOTE_TOOL_NAMES = {
    "create_training_session",
    "get_next_assignment",
    "submit_choice_answer",
    "record_interaction",
    "create_candidate_question",
    "revise_published_question",
    "finish_training_session",
    "get_learning_report",
    "get_scheduler_backlog",
}


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
    return [
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
                    "questionCount": integer_property("Number of main questions; defaults to 10"),
                    "domainId": string_property("Knowledge domain; defaults to java-backend"),
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
            "revise_published_question",
            "Create and publish an immutable next version of an existing question after explicit user approval.",
            schema(
                {
                    "questionId": string_property("Published question ID"),
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
        tool("get_scheduler_backlog", "Get due backlog and current new-item allowance.", schema({})),
    ]


def config_path():
    configured = os.environ.get("MINDTRAIN_PLUGIN_CONFIG")
    if configured:
        return Path(configured).expanduser()
    if os.name == "nt" and os.environ.get("APPDATA"):
        return Path(os.environ["APPDATA"]) / "MindTrain" / "plugin.json"
    root = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    return root / "mindtrain" / "plugin.json"


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
    data = json.loads(path.read_text(encoding="utf-8"))
    if not data.get("url") or not data.get("token"):
        raise ValueError("MindTrain plugin configuration is incomplete")
    return data


def save_config(url, token, path=None):
    path = path or config_path()
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    try:
        path.parent.chmod(0o700)
    except OSError:
        pass
    payload = json.dumps({"url": url, "token": token}, ensure_ascii=False, indent=2) + "\n"
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
        {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "mindtrain-plugin", "version": "0.1.0"}},
    )
    if result.get("serverInfo", {}).get("name") != "mindtrain-trainer-mcp":
        raise ValueError("The configured URL is not a MindTrain Trainer MCP server")


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
    return {"configured": True, "url": config["url"], "configPath": str(path)}


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
            verify_config(candidate)
            path = save_config(url, token)
            return content_result({"configured": True, "url": url, "configPath": str(path)})
        except (OSError, ValueError) as error:
            return content_result({"configured": False, "error": str(error)}, True)
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
        return remote_request(config, "tools/call", {"name": name, "arguments": arguments})
    except (OSError, ValueError, json.JSONDecodeError) as error:
        return content_result({"error": str(error)}, True)


def handle_request(request):
    method = request.get("method", "")
    if method == "initialize":
        return {
            "protocolVersion": request.get("params", {}).get("protocolVersion", "2025-03-26"),
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "mindtrain-plugin-bridge", "version": "0.1.0"},
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
