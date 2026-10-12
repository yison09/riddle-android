XML=/data/data/com.riddle.diary/shared_prefs/riddle.xml
BASE=$(sed -n 's|.*<string name="base">\(.*\)</string>.*|\1|p' $XML)
KEY=$(sed -n 's|.*<string name="key">\(.*\)</string>.*|\1|p' $XML)
URL="$BASE/chat/completions"
IMG='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='
test_one() {
  M="$1"
  printf '{"model":"%s","stream":false,"max_tokens":300,"messages":[{"role":"user","content":[{"type":"text","text":"describe the image in 3 words"},{"type":"image_url","image_url":{"url":"%s"}}]}]}' "$M" "$IMG" > /data/local/tmp/rm.json
  CODE=$(curl -s -m 30 -o /data/local/tmp/rm_out.json -w '%{http_code}' -X POST -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' --data-binary @/data/local/tmp/rm.json "$URL")
  SNIP=$(grep -o '"content":"[^"]*"' /data/local/tmp/rm_out.json | head -1 | cut -c1-60)
  test -z "$SNIP" && SNIP=$(head -c 140 /data/local/tmp/rm_out.json)
  echo "$M -> $CODE $SNIP"
}
test_one "grok-4.6"
test_one "gpt-5.6-terra"
test_one "nv/kimi-k3"
