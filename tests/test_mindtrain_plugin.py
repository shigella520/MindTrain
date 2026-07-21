import importlib.util
import json
import os
import stat
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import yaml


ROOT = Path(__file__).resolve().parents[1]
BRIDGE_PATH = ROOT / "plugins/mindtrain/scripts/mindtrain_mcp_bridge.py"
SPEC = importlib.util.spec_from_file_location("mindtrain_mcp_bridge", BRIDGE_PATH)
BRIDGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BRIDGE)


class MindTrainPluginTest(unittest.TestCase):
    def test_marketplace_plugin_and_mcp_manifests_are_consistent(self):
        marketplace = json.loads((ROOT / ".agents/plugins/marketplace.json").read_text())
        plugin = json.loads((ROOT / "plugins/mindtrain/.codex-plugin/plugin.json").read_text())
        mcp = json.loads((ROOT / "plugins/mindtrain/.mcp.json").read_text())

        self.assertEqual("mindtrain", marketplace["name"])
        self.assertEqual("mindtrain", marketplace["plugins"][0]["name"])
        self.assertEqual("./plugins/mindtrain", marketplace["plugins"][0]["source"]["path"])
        self.assertEqual("ON_USE", marketplace["plugins"][0]["policy"]["authentication"])
        self.assertEqual("mindtrain", plugin["name"])
        self.assertEqual("./.mcp.json", plugin["mcpServers"])
        self.assertIn("mindtrain", mcp["mcpServers"])
        for field in ("composerIcon", "logo", "logoDark"):
            asset = ROOT / "plugins/mindtrain" / plugin["interface"][field]
            self.assertTrue(asset.is_file(), f"missing plugin asset: {asset}")

    def test_skill_and_starter_prompts_use_the_mindtrain_name(self):
        plugin = json.loads((ROOT / "plugins/mindtrain/.codex-plugin/plugin.json").read_text())
        prompts = plugin["interface"]["defaultPrompt"]

        self.assertTrue(all("$mindtrain" in prompt for prompt in prompts))
        self.assertTrue(all(prompt.isascii() for prompt in prompts))
        self.assertFalse((ROOT / "plugins/mindtrain/skills/knowledge-trainer").exists())
        self.assertFalse((ROOT / "skills/mindtrain").exists())

        skill_path = ROOT / "plugins/mindtrain/skills/mindtrain/SKILL.md"
        frontmatter = skill_path.read_text().split("---", 2)[1]
        self.assertEqual("mindtrain", yaml.safe_load(frontmatter)["name"])

    def test_bridge_exposes_configuration_and_training_tools(self):
        names = {definition["name"] for definition in BRIDGE.tool_definitions()}
        self.assertIn("configure_mindtrain_instance", names)
        self.assertIn("get_mindtrain_configuration", names)
        self.assertEqual(BRIDGE.REMOTE_TOOL_NAMES, names - {
            "configure_mindtrain_instance", "get_mindtrain_configuration"
        })
        self.assertIn("revise_saved_question", names)
        self.assertIn("reject_generated_question", names)

    def test_url_requires_https_for_non_local_instances(self):
        self.assertEqual(
            "https://train.example.com/mcp",
            BRIDGE.normalize_url("https://train.example.com"),
        )
        self.assertEqual(
            "http://127.0.0.1:8787/mcp",
            BRIDGE.normalize_url("http://127.0.0.1:8787/mcp"),
        )
        with self.assertRaisesRegex(ValueError, "HTTPS is required"):
            BRIDGE.normalize_url("http://train.example.com/mcp")

    def test_configuration_is_saved_with_private_permissions_and_never_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "mindtrain" / "plugin.json"
            with patch.dict(os.environ, {"MINDTRAIN_PLUGIN_CONFIG": str(path)}):
                with patch.object(BRIDGE, "verify_config"):
                    result = BRIDGE.call_tool(
                        "configure_mindtrain_instance",
                        {"url": "https://train.example.com/mcp", "token": "secret-token"},
                    )

                self.assertFalse(result["isError"])
                self.assertEqual("secret-token", BRIDGE.load_config()["token"])
                status = BRIDGE.configuration_status()
                self.assertTrue(status["configured"])
                self.assertNotIn("token", status)
                if os.name != "nt":
                    self.assertEqual(stat.S_IRUSR | stat.S_IWUSR, stat.S_IMODE(path.stat().st_mode))


if __name__ == "__main__":
    unittest.main()
