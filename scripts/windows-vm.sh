#!/bin/bash
# A Windows 11 Arm64 virtual machine for running the Windows candidate window.
#
#   bash scripts/windows-vm.sh create        download and install (once)
#   bash scripts/windows-vm.sh start         boot headless, wait for SSH and the desktop
#   bash scripts/windows-vm.sh stop          shut Windows down
#   bash scripts/windows-vm.sh status
#   bash scripts/windows-vm.sh copy <dir> [<guest dir>]   default C:\meow\<name of dir>
#   bash scripts/windows-vm.sh run <command…>             cmd.exe in the guest; exit code passes through
#   bash scripts/windows-vm.sh deploy        .build/windows-check/mingw and the lexicon to C:\meow\preview
#   bash scripts/windows-vm.sh show <name> <preview options…>   preview in a fixed state, PNG around the caret
#   bash scripts/windows-vm.sh shots         three shapes, light and dark, collapsed and expanded
#   bash scripts/windows-vm.sh scale <100|125|150|175|200>      display scale; restarts Windows
#   bash scripts/windows-vm.sh screen <file.png>                the whole screen
#   bash scripts/windows-vm.sh ssh           interactive shell
#
# QEMU with Apple's hypervisor (hvf). Large files live in WINDOWS_VM_DIR,
# default <main checkout>/.build/windows-vm; screenshots go to SHOTS_DIR,
# default .build/windows-vm-shots in this checkout. The guest's user is
# "meow" with password "meow"; SSH on 127.0.0.1:2222 with a key made here.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$PWD
MAIN=$(cd "$(git rev-parse --git-common-dir)/.." && pwd)
VM="${WINDOWS_VM_DIR:-$MAIN/.build/windows-vm}"
SHOTS="${SHOTS_DIR:-$REPO/.build/windows-vm-shots}"
LEX="${MEOWPUUT_LEXICON_DIR:-$MAIN/iOS/Generated}"
PORT="${WINDOWS_VM_SSH_PORT:-2222}"
QEMU_SHARE=/opt/homebrew/share/qemu
MEDIA="$VM/media"
mkdir -p "$VM" "$MEDIA"

SSH=(ssh -q -i "$VM/ssh/id_ed25519" -p "$PORT" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null
     -o ConnectTimeout=5 -o ServerAliveInterval=10 meow@127.0.0.1)
SCP=(scp -q -r -i "$VM/ssh/id_ed25519" -P "$PORT" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null)

running() { [ -f "$VM/qemu.pid" ] && kill -0 "$(cat "$VM/qemu.pid")" 2>/dev/null; }

# One QMP command (JSON); prints the reply.
qmp() {
  python3 -I "$REPO/scripts/windows-vm/qmp.py" "$VM/qmp.sock" "$1"
}

# Boots QEMU in the background. $1: an installer ISO to attach (with data.dmg).
boot() {
  local cd=()
  if [ -n "${1:-}" ]; then
    cd=(-device usb-bot,id=cd -drive "if=none,id=install,media=cdrom,readonly=on,file=$1"
        -device scsi-cd,bus=cd.0,drive=install,bootindex=1
        -drive "if=none,id=data,format=raw,readonly=on,file=$MEDIA/data.dmg" -device usb-storage,drive=data)
  fi
  rm -f "$VM/qmp.sock"
  qemu-system-aarch64 -name meowpuut-windows -machine virt,highmem=on -accel hvf -cpu host \
    -smp "${WINDOWS_VM_CPUS:-4}" -m "${WINDOWS_VM_MEMORY:-6144}" \
    -drive "if=pflash,format=raw,readonly=on,file=$QEMU_SHARE/edk2-aarch64-code.fd" \
    -drive "if=pflash,format=raw,file=$VM/vars.fd" \
    -device ramfb -device qemu-xhci -device usb-kbd -device usb-tablet \
    -drive "if=none,id=disk,format=qcow2,file=$VM/disk.qcow2" -device nvme,drive=disk,serial=meowpuut,bootindex=0 \
    "${cd[@]}" \
    -netdev "user,id=net0,hostfwd=tcp:127.0.0.1:$PORT-:22" -device "${WINDOWS_VM_NIC:-virtio-net-pci},netdev=net0" \
    ${WINDOWS_VM_EXTRA:-} \
    -rtc base=localtime -display none -serial "file:$VM/serial.log" \
    -qmp "unix:$VM/qmp.sock,server=on,wait=off" -pidfile "$VM/qemu.pid" -daemonize
}

wait_ssh() {
  local limit=${1:-600} deadline=$((SECONDS + ${1:-600}))
  until "${SSH[@]}" exit 2>/dev/null; do
    [ $SECONDS -lt $deadline ] || { echo "error: no SSH after $limit s" >&2; return 1; }
    running || { echo "error: QEMU stopped" >&2; return 1; }
    sleep 5
  done
}

# The desktop is up once explorer.exe runs in the console session.
wait_desktop() {
  local deadline=$((SECONDS + 300))
  until "${SSH[@]}" 'tasklist /fi "imagename eq explorer.exe" /fi "session eq 1" | find "explorer.exe"' >/dev/null 2>&1; do
    [ $SECONDS -lt $deadline ] || { echo "error: no desktop after 300 s" >&2; return 1; }
    sleep 3
  done
  sleep 5
}

cmd_start() {
  if running; then wait_ssh; wait_desktop; return; fi
  [ -f "$VM/installed" ] || { echo "error: no VM in $VM; run: bash scripts/windows-vm.sh create" >&2; exit 1; }
  local t=$SECONDS
  boot
  wait_ssh 600
  wait_desktop
  echo "Windows is up ($((SECONDS - t)) s)"
}

cmd_stop() {
  running || { echo "not running"; return; }
  "${SSH[@]}" 'shutdown /s /t 0' 2>/dev/null || qmp '{"execute":"system_powerdown"}' >/dev/null
  local deadline=$((SECONDS + 120))
  while running && [ $SECONDS -lt $deadline ]; do sleep 2; done
  if running; then qmp '{"execute":"quit"}' >/dev/null || true; fi
  rm -f "$VM/qemu.pid"
  echo "stopped"
}

cmd_copy() {
  local src=$1 dest=${2:-"C:\\meow\\$(basename "$1")"}
  "${SSH[@]}" "if exist \"$dest\" rmdir /s /q \"$dest\"" >/dev/null
  # scp wants forward slashes in the guest path.
  "${SCP[@]}" "$src" "meow@127.0.0.1:${dest//\\//}"
  echo "copied $src to $dest"
}

cmd_run() { "${SSH[@]}" "$*"; }

cmd_deploy() {
  local mw="$REPO/.build/windows-check/mingw" stage="$VM/stage/preview"
  [ -f "$mw/MeowpuutCandidatePreview.exe" ] || { echo "error: run scripts/build-windows-check.sh first" >&2; exit 1; }
  rm -rf "$stage" && mkdir -p "$stage/lexicon"
  cp "$mw"/*.exe "$stage/" && cp -R "$mw/Assets" "$stage/"
  cp "$LEX"/native-{pinyin,zhuyin,japanese}.tsv "$stage/lexicon/"
  cmd_copy "$stage" 'C:\meow\preview'
}

cmd_screen() {
  qmp "{\"execute\":\"screendump\",\"arguments\":{\"filename\":\"$1\",\"format\":\"png\"}}" >/dev/null
  echo "$1"
}

# Display scale of the signed-in user, in percent.
guest_scale() {
  local dpi
  dpi=$("${SSH[@]}" 'reg query "HKCU\Control Panel\Desktop" /v LogPixels' 2>/dev/null | awk '/LogPixels/{print $3}')
  echo $(( ${dpi:-0x60} * 100 / 96 ))
}

# Runs the preview in the signed-in desktop session (through a scheduled task:
# programs started over SSH cannot show windows), takes a screenshot with
# QEMU and crops it around the caret. $1: output name; the rest go to the
# preview (see the top of Windows/Preview/PreviewApp.cpp). MOUSE=x,y in
# screen pixels moves the pointer there first.
cmd_show() {
  local name=$1; shift
  local scale; scale=$(guest_scale)
  local x=$((160 * scale / 100)) y=$((200 * scale / 100))
  mkdir -p "$SHOTS/screens"
  python3 -I "$REPO/scripts/windows-vm/show_script.py" "$VM/show.ps1" --at "$x,$y" --seconds 120 "$@"
  "${SCP[@]}" "$VM/show.ps1" "meow@127.0.0.1:C:/meow/show.ps1"
  "${SSH[@]}" 'powershell -NoProfile -ExecutionPolicy Bypass -File C:\meow\show.ps1'
  local full="$SHOTS/screens/$name.png"
  cmd_screen "$full" >/dev/null
  local size; size=$(python3 -I -c 'import sys; from PIL import Image; print(*Image.open(sys.argv[1]).size)' "$full")
  local w=${size% *} h=${size#* } mx=${MOUSE:-"$((w - 1)),$((h - 1))"}
  qmp "{\"execute\":\"input-send-event\",\"arguments\":{\"events\":[
    {\"type\":\"abs\",\"data\":{\"axis\":\"x\",\"value\":$(( ${mx%,*} * 32767 / w ))}},
    {\"type\":\"abs\",\"data\":{\"axis\":\"y\",\"value\":$(( ${mx#*,} * 32767 / h ))}}]}}" >/dev/null
  sleep 1
  cmd_screen "$full" >/dev/null
  "${SSH[@]}" 'taskkill /im MeowpuutCandidatePreview.exe /f' >/dev/null 2>&1 || true
  # The candidate window and its expanded panel fit in 860 x 420 pt from the caret.
  python3 -I -c '
import sys
from PIL import Image
x, y, s = int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5]) / 100
im = Image.open(sys.argv[1])
im.crop((max(0, x - int(90 * s)), max(0, y - int(50 * s)), min(im.width, x + int(770 * s)), min(im.height, y + int(370 * s)))).save(sys.argv[2])
' "$full" "$SHOTS/$name.png" "$x" "$y" "$scale"
  echo "$SHOTS/$name.png"
}

SUSHI='蘇軾,素食,速食,宿舍,訴說,塑身,素什,ja:寿司'
cmd_shots() {
  local scale shape tone; scale=$(guest_scale)
  for shape in capsule wave cloud; do
    for tone in light dark; do
      cmd_show "$scale-$tone-$shape" --shape $shape --tone $tone --candidates "$SUSHI" --preedit sushi
      cmd_show "$scale-$tone-$shape-expanded" --shape $shape --tone $tone --candidates "$SUSHI" --preedit sushi \
        --expanded --highlight 7 --page 1 --more
    done
  done
}

cmd_scale() {
  local dpi=$(( $1 * 96 / 100 ))
  "${SSH[@]}" "reg add \"HKCU\\Control Panel\\Desktop\" /v LogPixels /t REG_DWORD /d $dpi /f >nul && reg add \"HKCU\\Control Panel\\Desktop\" /v Win8DpiScaling /t REG_DWORD /d 1 /f >nul && shutdown /r /t 0"
  sleep 20
  wait_ssh 600
  wait_desktop
  echo "scale $1 %"
}

cmd_status() {
  if running; then echo "running (pid $(cat "$VM/qemu.pid"))"; else echo "stopped"; fi
  du -sh "$VM"/disk.qcow2 "$MEDIA" 2>/dev/null || true
}

# --- creation -------------------------------------------------------------

# The zh-TW Arm64 ESD listed in Microsoft's Media Creation Tool catalog, the
# virtio network driver (Windows has none for QEMU's virtio-net) and
# Microsoft's OpenSSH for Windows.
fetch_media() {
  cd "$MEDIA"
  [ -f products.cab ] || curl -fsSL -o products.cab "https://go.microsoft.com/fwlink/?LinkId=2156292"
  [ -f products.xml ] || tar -xf products.cab products.xml
  local url sha
  read -r url sha < <(python3 -I -c '
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
for f in re.findall(r"<File id=\"[^\"]*\">(.*?)</File>", xml, re.S):
    tag = lambda t: re.search(f"<{t}>(.*?)</{t}>", f, re.S).group(1)
    if tag("Architecture") == "ARM64" and tag("LanguageCode") == "zh-tw" and tag("Edition") == "Professional":
        print(tag("FilePath"), tag("Sha1")); break
' products.xml)
  if [ ! -f windows.esd ]; then
    echo "downloading $url"
    curl -fL -C - -o windows.esd.part "$url"
    [ "$(shasum -a 1 windows.esd.part | cut -d' ' -f1)" = "$sha" ] || { echo "error: SHA-1 mismatch" >&2; exit 1; }
    mv windows.esd.part windows.esd
  fi
  [ -f virtio-win.iso ] || curl -fL -o virtio-win.iso \
    https://fedorapeople.org/groups/virt/virtio-win/direct-downloads/stable-virtio/virtio-win.iso
  [ -f OpenSSH-ARM64.zip ] || curl -fL -o OpenSSH-ARM64.zip \
    https://github.com/PowerShell/Win32-OpenSSH/releases/latest/download/OpenSSH-ARM64.zip
  cd "$REPO"
}

# install.iso: boot files and Windows PE from the ESD. data.dmg: an exFAT disk
# with the ESD itself (over 4 GB, too big for ISO 9660), the network driver,
# OpenSSH and the answer file. Windows PE runs deploy.cmd from data.dmg, which
# partitions the disk, applies Windows 11 Pro and powers off. Windows Setup
# does not run, so there is no product key or edition page.
build_media() {
  [ -f "$MEDIA/install.iso" ] && [ -f "$MEDIA/data.dmg" ] && return
  local t="$MEDIA/iso" d="$MEDIA/data"
  chmod -R u+w "$d" 2>/dev/null || true
  rm -rf "$t" "$d" "$MEDIA/data.dmg" && mkdir -p "$d/meow/drivers"
  wimlib-imagex apply "$MEDIA/windows.esd" 1 "$t" >/dev/null 2>&1
  rm -rf "$t/__chunk_data"
  wimlib-imagex export "$MEDIA/windows.esd" 2 "$t/sources/boot.wim" --compress=LZX --boot >/dev/null
  wimlib-imagex update "$t/sources/boot.wim" 1 \
    --command="add $REPO/scripts/windows-vm/startnet.cmd /Windows/System32/startnet.cmd" >/dev/null
  # efisys_noprompt: no "press any key to boot from CD" wait.
  xorriso -as mkisofs -iso-level 3 -J -joliet-long -V MEOWPUUT_WIN11 \
    -e efi/microsoft/boot/efisys_noprompt.bin -no-emul-boot -o "$MEDIA/install.iso" "$t" 2>/dev/null
  ln "$MEDIA/windows.esd" "$d/windows.esd"
  xorriso -osirrox on -indev "$MEDIA/virtio-win.iso" -extract /NetKVM/w11/ARM64 "$d/meow/drivers/netkvm" 2>/dev/null
  chmod -R u+w "$d/meow/drivers"
  cp "$MEDIA/OpenSSH-ARM64.zip" "$REPO"/scripts/windows-vm/{deploy.cmd,unattend.xml,firstlogon.ps1} "$d/meow/"
  cp "$VM/ssh/id_ed25519.pub" "$d/meow/authorized_keys"
  hdiutil create -quiet -srcfolder "$d" -fs ExFAT -volname MEOWDATA -format UDRW -layout GPTSPUD "$MEDIA/data.dmg"
  rm -rf "$t" "$d"
}

cmd_create() {
  if running; then echo "error: the VM is running" >&2; exit 1; fi
  for tool in qemu-system-aarch64 wimlib-imagex xorriso; do
    command -v $tool >/dev/null || { echo "error: $tool missing; brew install qemu wimlib xorriso" >&2; exit 1; }
  done
  mkdir -p "$VM/ssh"
  [ -f "$VM/ssh/id_ed25519" ] || ssh-keygen -q -t ed25519 -N '' -C meowpuut-windows-vm -f "$VM/ssh/id_ed25519"
  fetch_media
  build_media
  if [ ! -f "$VM/installed" ]; then
    qemu-img create -q -f qcow2 "$VM/disk.qcow2" 64G
    cp "$QEMU_SHARE/edk2-arm-vars.fd" "$VM/vars.fd"
    echo "Windows PE applies the image, then powers off"
    local t=$SECONDS
    boot "$MEDIA/install.iso"
    while running; do
      if [ $((SECONDS - t)) -gt 3600 ]; then
        cmd_screen "$VM/install-stuck.png"
        echo "error: Windows PE did not finish in an hour; see the screenshot above" >&2
        exit 1
      fi
      sleep 15
    done
    touch "$VM/installed"
    echo "image applied in $((SECONDS - t)) s"
  fi
  echo "first boot: Windows sets itself up, signs in and installs OpenSSH"
  local t=$SECONDS
  boot
  wait_ssh 3600
  wait_desktop
  echo "ready after $((SECONDS - t)) s"
}

case "${1:-}" in
  create) cmd_create ;;
  start) cmd_start ;;
  stop) cmd_stop ;;
  status) cmd_status ;;
  copy) shift; cmd_copy "$@" ;;
  run) shift; cmd_run "$@" ;;
  deploy) cmd_deploy ;;
  show) shift; cmd_show "$@" ;;
  shots) cmd_shots ;;
  scale) cmd_scale "$2" ;;
  screen) cmd_screen "$2" ;;
  ssh) exec ssh -i "$VM/ssh/id_ed25519" -p "$PORT" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null meow@127.0.0.1 ;;
  *) sed -n '2,15p' "$0"; exit 1 ;;
esac
