import csv
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
JAR = ROOT / "build/code-review-agent.jar"
SCRIPT = ROOT / "scripts/review-eval.py"

STUB = """#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

prompt = sys.stdin.read()
assert "VERIFIED_REFERENCE_ONLY" not in prompt
if prompt.startswith("You are preparing scoring criteria"):
    assert not (Path.cwd() / ".git").exists()
    assert "FOCUS_ONLY" in prompt and "return sum(items) // len(items)" in prompt
    with Path(os.environ["RUBRIC_CALLS"]).open("a") as handle:
        handle.write("draft\\n")
    if os.environ.get("INVALID_RUBRIC"):
        print('{"criteria": []}')
    else:
        print(chr(96) * 3 + "json")
        print(json.dumps(["RUBRIC_ONLY_CRITERION", "Check the trigger", "Check the source evidence"]))
        print(chr(96) * 3)
else:
    assert "RUBRIC_ONLY_CRITERION" not in prompt and "FOCUS_ONLY" not in prompt
    assert "LATER_COMMIT_ONLY" not in prompt and "LIVE_DOCUMENT_EDIT" not in prompt
    if prompt.startswith("You are the discovery stage"):
        assert "return sum(items) // len(items)" in prompt
        assert "SNAPSHOTTED_KNOWLEDGE" in prompt
        print("<findings/>")
    elif prompt.startswith("You are the report consolidation"):
        for heading in ("Review scope", "Functional and business change", "Findings",
                        "Requirement assessment", "Knowledge-base feedback", "Verification limits"):
            print("# " + heading + "\\n")
        print("<finding-groups/>")
    else:
        raise AssertionError("Unexpected model call")
"""

PR_AGENT_STUB = '''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

args = sys.argv[1:]
if args == ["--version"]:
    print("pr-agent test fixture")
    raise SystemExit(0)
assert args[-1] == "review"
def path(flag):
    return Path(args[args.index(flag) + 1])

assert "return sum(items) // len(items)" in path("--diff-file").read_text()
assert Path("cart.py").read_text() == "def cart_total(items):\\n    return sum(items) // len(items)\\n"
assert not Path(".git").exists()
context = os.environ["PLAIN_DIFF__DESCRIPTION"]
assert "SNAPSHOTTED_KNOWLEDGE" in context
assert all(marker not in context for marker in ("VERIFIED_REFERENCE_ONLY", "FOCUS_ONLY", "LIVE_DOCUMENT_EDIT"))
path("--output").write_text("Review report\\n\\n<details> <summary>Agent run details</summary>\\n"
                           "- Model: IDENTITY_ONLY\\n</details>\\n")
if not os.getenv("BROKEN_PR_AGENT"):
    path("--json-output").write_text(json.dumps({
        "review": {}, "usage": {"total_tokens": 75},
        "coverage": {"files_not_reviewed": [], "files_partially_reviewed": ["cart.py"]},
    }))
'''


class ReviewEvalTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not JAR.is_file():
            raise RuntimeError("Build the CLI first with bash scripts/build.sh")

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.repo = self.root / "target repo"
        self.repo.mkdir()
        self.source = self.repo / "cart.py"
        self.git("init", "-q")
        self.source.write_text("def cart_total(items):\n    return sum(items)\n", encoding="utf-8")
        self.commit("base")
        self.base = self.git("rev-parse", "HEAD").strip()
        self.source.write_text("def cart_total(items):\n    return sum(items) // len(items)\n", encoding="utf-8")
        self.commit("target")
        self.head = self.git("rev-parse", "HEAD").strip()
        self.knowledge = self.root / "knowledge.md"
        self.knowledge.write_text("SNAPSHOTTED_KNOWLEDGE\n", encoding="utf-8")
        self.rubric_calls = self.root / "rubric-calls"
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        stub = bin_dir / "claude"
        stub.write_text(STUB, encoding="utf-8")
        stub.chmod(0o700)
        self.env = {**os.environ, "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
                    "RUBRIC_CALLS": str(self.rubric_calls)}

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.repo), "-c", "user.name=Fixture",
                               "-c", "user.email=fixture@example.invalid", *args],
                              check=True, capture_output=True, text=True).stdout

    def commit(self, message):
        self.git("add", "cart.py")
        self.git("commit", "-qm", message)

    def command(self, *args, expected=0):
        result = subprocess.run([sys.executable, str(SCRIPT), *map(str, args)], cwd=ROOT, env=self.env,
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, expected, result.stderr)
        return result

    def prepare(self, name, *args, base=None, expected=0):
        return self.command("prepare", "--repo", self.repo, "--base", base or self.base,
                            "--focus", "FOCUS_ONLY", "--knowledge", self.knowledge,
                            "--output", self.root / name, *args, expected=expected)

    def run_case(self, manifest, name, *args, expected=0):
        result = self.root / name
        self.command("run", "--cases", manifest, "--baseline-jar", JAR,
                     "--candidate-jar", JAR, "--output", result, *args, expected=expected)
        return result

    def test_prepared_case_pins_inputs_and_keeps_rubric_out_of_reviews(self):
        manifest = Path(self.prepare("case").stdout.strip())
        case, = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual((case["base"], case["head"]), (self.base, self.head))
        self.assertFalse(case["score_missed_defects"])
        self.assertEqual(case["criteria"][0], "RUBRIC_ONLY_CRITERION")
        self.source.write_text("LATER_COMMIT_ONLY\n", encoding="utf-8")
        self.commit("head moved")
        self.knowledge.write_text("LIVE_DOCUMENT_EDIT\n", encoding="utf-8")

        output = self.run_case(manifest, "comparison")
        rubric = output / "blind" / case["id"] / "criteria.md"
        self.assertIn("FOCUS_ONLY", rubric.read_text(encoding="utf-8"))
        results = json.loads((output / "private/results.json").read_text(encoding="utf-8"))
        self.assertEqual([result["status"] for result in results], ["COMPLETE", "COMPLETE"])
        grades = output / "blind/grades.csv"
        with grades.open(encoding="utf-8", newline="") as handle:
            reader = csv.DictReader(handle)
            fields, rows = reader.fieldnames, list(reader)
        self.assertEqual([row["missed_defects"] for row in rows], ["N/A", "N/A"])
        rows[0].update(false_positives="0", unmet_criteria="1")
        rows[1].update(unmet_criteria="2")
        with grades.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)
        summary = self.command("summarize", "--output", output).stdout
        self.assertIn("Scored pairs: 1/1", summary)
        self.assertEqual(summary.count("Total missed_defects: unscored"), 2)
        self.assertEqual(summary.count("Total false_positives: unscored"), 2)
        self.assertIn("Total unmet_criteria: 1 (n=1)", summary)
        self.assertIn("Total unmet_criteria: 2 (n=1)", summary)
        grades.write_text(grades.read_text(encoding="utf-8").replace("N/A", "0"), encoding="utf-8")
        rejected = self.command("summarize", "--output", output, expected=2)
        self.assertIn("Missed defects are N/A", rejected.stderr)

    def test_three_way_comparison_uses_pinned_pr_agent_inputs_and_pairs_scores(self):
        pr_agent = self.root / "test pr-agent"
        pr_agent.write_text(PR_AGENT_STUB, encoding="utf-8")
        pr_agent.chmod(0o700)
        reference = self.root / "verified.md"
        reference.write_text("VERIFIED_REFERENCE_ONLY\n", encoding="utf-8")
        manifest = Path(self.prepare("three-way", "--category", "small", "--reference", reference,
                                     "--reference-defects", "1").stdout.strip())
        self.source.write_text("LATER_COMMIT_ONLY\n", encoding="utf-8")
        self.commit("head moved")
        self.knowledge.write_text("LIVE_DOCUMENT_EDIT\n", encoding="utf-8")

        output = self.run_case(manifest, "three-way-output", "--pr-agent", pr_agent)
        records = json.loads((output / "private/results.json").read_text(encoding="utf-8"))
        self.assertEqual({row["report"] for row in records}, {"A", "B", "C"})
        peer = next(row for row in records if row["variant"] == "pr-agent")
        self.assertEqual((peer["status"], peer["total_tokens"]), ("INCOMPLETE", 75))
        blind = output / "blind" / peer["case"] / peer["trial"] / (peer["report"] + ".md")
        self.assertNotIn("IDENTITY_ONLY", blind.read_text(encoding="utf-8"))
        self.assertTrue(blind.is_file())

        grades = output / "blind/grades.csv"
        with grades.open(encoding="utf-8", newline="") as handle:
            reader = csv.DictReader(handle)
            fields, rows = reader.fieldnames, list(reader)
        for row in rows:
            row.update(missed_defects="0", false_positives="0", reviewer_seconds="12")
        with grades.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)
        costs = self.root / "measured-costs.csv"
        costs.write_text("case,trial,variant,cost_usd\n" + "".join(
            f"{row['case']},{row['trial']},{row['variant']},0.02\n" for row in records
        ), encoding="utf-8")
        summary = self.command("summarize", "--output", output, "--costs", costs).stdout
        self.assertIn("candidate vs pr-agent — small", summary)
        self.assertIn("p50 elapsed_seconds:", summary)
        self.assertIn("Known-defect recall: 1/1 (100.0%)", summary)
        self.assertIn("Total reviewer_seconds: 12 (n=1)", summary)
        self.assertIn("Mean cost_usd: 0.020000 (n=1)", summary)

        self.env["BROKEN_PR_AGENT"] = "1"
        output = self.run_case(manifest, "three-way-failed", "--pr-agent", pr_agent, expected=1)
        summary = self.command("summarize", "--output", output).stdout
        self.assertIn("baseline vs candidate — all cases\nUsable pairs: 1/1", summary)
        self.assertIn("baseline vs pr-agent — all cases\nUsable pairs: 0/1", summary)

    def test_verified_reference_goes_to_grader_only(self):
        reference = self.root / "verified.md"
        reference.write_text("VERIFIED_REFERENCE_ONLY\n", encoding="utf-8")
        manifest = Path(self.prepare("verified-case", "--reference", reference).stdout.strip())
        case, = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertTrue(case["score_missed_defects"])
        reference.write_text("Changed after preparation\n", encoding="utf-8")
        output = self.run_case(manifest, "verified-comparison")
        saved = output / "blind" / case["id"] / "reference.md"
        self.assertEqual(saved.read_text(encoding="utf-8"), "VERIFIED_REFERENCE_ONLY\n")
        self.assertNotIn("N/A", (output / "blind/grades.csv").read_text(encoding="utf-8"))

    def test_empty_diff_and_invalid_rubric_do_not_create_a_case(self):
        empty = self.prepare("empty-case", base=self.head, expected=2)
        self.assertIn("no file differences", empty.stderr)
        self.assertFalse(self.rubric_calls.exists())
        self.assertFalse((self.root / "empty-case/cases.json").exists())
        self.env["INVALID_RUBRIC"] = "1"
        invalid = self.prepare("invalid-case", expected=2)
        self.assertIn("3 to 6 nonempty scoring criteria", invalid.stderr)
        self.assertFalse((self.root / "invalid-case/cases.json").exists())


if __name__ == "__main__":
    unittest.main()
