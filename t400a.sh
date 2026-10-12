XML=/data/data/com.riddle.diary/shared_prefs/riddle.xml
BASE=$(sed -n 's|.*<string name="base">\(.*\)</string>.*|\1|p' $XML)
KEY=$(sed -n 's|.*<string name="key">\(.*\)</string>.*|\1|p' $XML)
URL="$BASE/chat/completions"
IMG='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='
# App 实际发的形态: stream=true + image + max_tokens=2000
printf '{"model":"mimo-v2.6-flash","stream":true,"max_tokens":2000,"messages":[{"role":"user","content":[{"type":"text","text":"Reply to what is written in the diary."},{"type":"image_url","image_url":{"url":"%s"}}]}]}' "$IMG" > /data/local/tmp/t1.json
curl -s -N -m 60 -o /data/local/tmp/t1.out -w 'stream_img_2000:%{http_code} t=%{time_total}\n' -X POST -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' --data-binary @/data/local/tmp/t1.json "$URL"
wc -c /data/local/tmp/t1.out
cat /data/local/tmp/t1.out
echo
echo '--- tail ---'
tail -c 1500 /data/local/tmp/t1.out
