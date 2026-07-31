#!/usr/bin/env bash
set -euo pipefail

config_dir="${TWS_CONFIG_DIR:-$HOME/.tws}"
vm_options="$config_dir/tws.vmoptions"

mkdir -p "$config_dir"
if [[ ! -e "$vm_options" ]]; then
  cp @libexec@/tws.vmoptions "$vm_options"
fi

sed -i -e "s#-DvmOptionsPath=.*#-DvmOptionsPath=$vm_options#" "$vm_options"

export INSTALL4J_JAVA_HOME_OVERRIDE=@libexec@/jre
export LD_LIBRARY_PATH=/usr/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}

exec @libexec@/tws \
  -J-DjtsConfigDir="$config_dir" \
  -J-Djxbrowser.chromium.dir="${XDG_CACHE_HOME:-$HOME/.cache}/ib-tws/jxbrowser" \
  -J-Dawt.useSystemAAFontSettings=lcd \
  -J-Dswing.aatext=true \
  "$@"
