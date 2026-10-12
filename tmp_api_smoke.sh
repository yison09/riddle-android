XML=/data/data/com.riddle.diary/shared_prefs/riddle.xml
BASE=$(sed -n 's|.*<string name="base">\(.*\)</string>.*|\1|p' "$XML")
KEY=$(sed -n 's|.*<string name="key">\(.*\)</string>.*|\1|p' "$XML")
echo "host=$(echo "$BASE" | sed 's|https\?://||')"
command -v curl >/dev/null && echo HAS_CURL || echo NO_CURL
curl -s -m 10 -o /data/local/tmp/m.json -w "models:%{http_code}\n" -H "Authorization: Bearer $KEY" "$BASE/models"
head -c 200 /data/local/tmp/m.json; echo
printf '{"model":"mimo-v2.6-flash","stream":false,"max_tokens":8,"messages":[{"role":"user","content":"say hi"}]}' > /data/local/tmp/r1.json
curl -s -m 25 -o /data/local/tmp/c1.json -w "chat_nostream:%{http_code} t=%{time_total}\n" -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" --data-binary @/data/local/tmp/r1.json "$BASE/chat/completions"
head -c 300 /data/local/tmp/c1.json; echo
IMG=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==
printf '{"model":"mimo-v2.6-flash","stream":true,"max_tokens":16,"messages":[{"role":"user","content":[{"type":"text","text":"hi"},{"type":"image_url","image_url":{"url":"'"$IMG"'"}}]}]}' > /data/local/tmp/r2.json
curl -s -N -m 30 -o /data/local/tmp/c2.json -w "chat_stream_img:%{http_code} t=%{time_total}\n" -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" --data-binary @/data/local/tmp/r2.json "$BASE/chat/completions"
head -c 400 /data/local/tmp/c2.json; echo
