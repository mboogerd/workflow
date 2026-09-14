#!/usr/bin/env bash
set -euo pipefail
build_dir="$1"
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
launcher="$build_dir/install/workflow/bin/workflow"
test -x "$launcher"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
cp -R "$root_dir/examples" "$scratch/examples"
cd "$scratch"
"$launcher" validate examples/hello-expression.yaml --providers demo
"$launcher" validate examples/single-repository.yaml --providers demo
"$launcher" validate examples/multi-repository.yaml --providers demo
"$launcher" validate examples/continuous-repositories.yaml --providers demo
"$launcher" compile examples/hello-expression.yaml --output "$scratch/hello.ir.json" --providers demo >/dev/null
"$launcher" run examples/hello-expression.yaml --parameters examples/hello-parameters.json --providers demo >/dev/null
"$launcher" run examples/single-repository.yaml --parameters examples/single-repository-parameters.json --providers demo >/dev/null
"$launcher" run examples/multi-repository.yaml --parameters examples/multi-repository-parameters.json --providers demo >/dev/null
database="$scratch/continuous.db"
"$launcher" demo continuous-repositories start --database "$database" >/dev/null
"$launcher" demo continuous-repositories resume --database "$database" >/dev/null
safety_file="$scratch/release-safety.json"
"$launcher" demo continuous-repositories emit --database "$database" --event '{"repository":"app/service","branch":"main","deliveryId":"release-safety","commit":"release-fault-a"}' >"$safety_file"
grep -q '"externalWrites":1' "$safety_file"
grep -q '"type":"retry_scheduled"' "$safety_file"
grep -q '"type":"reconciliation_decision"' "$safety_file"
"$launcher" demo continuous-repositories inspect --database "$database" >/dev/null
"$launcher" replay "$database" >/dev/null
"$launcher" demo continuous-repositories stop --database "$database" >/dev/null
echo "distribution smoke passed"
