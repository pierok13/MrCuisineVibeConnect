#!/system/bin/sh
# Toque : à chaque démarrage, Magisk (v31, Android 6) efface l'autorisation root des applis, puis
# redemande à Toque et refuse au bout de 10 s si personne ne répond. On la remet pendant 3 minutes.
(
  i=0
  while [ $i -lt 180 ]; do
    U=$(stat -c %u /data/data/app.toque 2>/dev/null)
    [ -n "$U" ] && magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($U,2,0,0,0)" >/dev/null 2>&1
    sleep 1
    i=$((i+1))
  done
) </dev/null >/dev/null 2>&1 &
