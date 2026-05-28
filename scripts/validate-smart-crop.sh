#!/usr/bin/env bash
# Validates smart crop quality by submitting real images through the full pipeline,
# downloading the 128x128 results, and saving them for visual inspection.
set -euo pipefail

API="http://localhost:8080"
KEY="${API_KEY:-changeme}"
OUT="validation/output"
mkdir -p "$OUT"

# Test cases: label → URL
declare -A IMAGES=(
  ["portrait-single"]="https://i.pravatar.cc/600"
  ["portrait-large"]="https://i.pravatar.cc/1200"
  ["landscape-nature"]="https://picsum.photos/seed/forest42/800/600"
  ["landscape-wide"]="https://picsum.photos/seed/mountain7/1200/400"
)

submit() {
  local label="$1" url="$2"
  local job_id
  job_id=$(curl -sf -X POST "$API/api/jobs" \
    -H "X-Api-Key: $KEY" \
    --data-urlencode "url=$url" | python3 -c "import sys,json; print(json.load(sys.stdin)['jobId'])")
  echo "$job_id $label"
}

poll() {
  local job_id="$1" label="$2" max=30 i=0
  while [[ $i -lt $max ]]; do
    local status
    status=$(curl -sf "$API/api/jobs/$job_id/status" -H "X-Api-Key: $KEY" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
    echo "  [$label] attempt $((i+1)): $status"
    case "$status" in
      COMPLETED)
        curl -sf "$API/api/jobs/$job_id/result" -H "X-Api-Key: $KEY" -o "$OUT/$label.png"
        echo "  [$label] saved → $OUT/$label.png"
        return 0
        ;;
      FAILED)
        echo "  [$label] FAILED after $i polls"
        return 1
        ;;
    esac
    sleep 3
    ((i++))
  done
  echo "  [$label] timed out"
  return 1
}

echo "=== Waiting for API to be ready ==="
for i in $(seq 1 20); do
  if curl -sf "$API/actuator/health" -H "X-Api-Key: $KEY" | grep -q '"UP"'; then
    echo "API ready."
    break
  fi
  echo "  waiting ($i/20)..."
  sleep 5
done

echo ""
echo "=== Submitting jobs ==="
declare -A JOBS
for label in "${!IMAGES[@]}"; do
  url="${IMAGES[$label]}"
  read -r job_id _ < <(submit "$label" "$url")
  JOBS["$label"]="$job_id"
  echo "  [$label] job_id=$job_id  url=$url"
done

echo ""
echo "=== Polling for results ==="
pass=0; fail=0
for label in "${!JOBS[@]}"; do
  if poll "${JOBS[$label]}" "$label"; then
    ((pass++))
  else
    ((fail++))
  fi
done

echo ""
echo "=== Results written to $OUT/ ==="
ls -lh "$OUT/"
echo ""
echo "Passed: $pass  Failed: $fail"
echo "Open the PNGs to inspect crop quality:"
echo "  - portrait-* should show the face centered, head-and-shoulders framing"
echo "  - landscape-* should be a centered square crop of the image"
