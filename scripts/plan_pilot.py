#!/usr/bin/env python3
"""Generate an offline AWS provider plan. Never applies, refreshes, or reads AWS state."""
import argparse
import json
import os
import pathlib
import subprocess
import sys


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=pathlib.Path, default=pathlib.Path("infra/pilot"))
    parser.add_argument("--variables", default="proposed.tfvars.json")
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--terraform", default="terraform")
    args = parser.parse_args(argv)
    directory = args.directory.resolve()
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    binary = output.with_suffix(".tfplan")
    env = {k: v for k, v in os.environ.items() if not k.startswith("AWS_") and not k.startswith("TF_VAR_") and not k.startswith("TF_CLI_ARGS")}
    env["AWS_EC2_METADATA_DISABLED"] = "true"
    try:
        variables = pathlib.Path(args.variables)
        if not variables.is_absolute():
            variables = directory / variables
        settings = json.loads(variables.read_text(encoding="utf-8-sig"))
        if not isinstance(settings, dict) or set(settings) != {"publicly_accessible", "multi_az"} or any(type(v) is not bool for v in settings.values()):
            raise ValueError("Pilot variables must contain exactly two booleans: publicly_accessible and multi_az")
        subprocess.run([args.terraform, "init", "-backend=false", "-input=false", "-lockfile=readonly"], cwd=directory, env=env, check=True)
        subprocess.run([args.terraform, "plan", "-input=false", "-refresh=false", "-lock=false",
                        f"-var-file={variables}", "-var=offline=true", f"-out={binary}"], cwd=directory, env=env, check=True)
        # Do not use shell redirection: Windows PowerShell can produce UTF-16 JSON.
        with output.open("wb") as stream:
            subprocess.run([args.terraform, "show", "-json", str(binary)], cwd=directory, env=env, stdout=stream, check=True)
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        output.unlink(missing_ok=True)
        print(f"Pilot plan generation failed: {type(error).__name__}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
