# Sends one QMP command to QEMU and prints the reply.
#   python3 qmp.py <socket> '<json command>'
import json
import socket
import sys

s = socket.socket(socket.AF_UNIX)
s.connect(sys.argv[1])
f = s.makefile('rw')
f.readline()  # greeting
for command in ('{"execute":"qmp_capabilities"}', sys.argv[2]):
    f.write(command + '\n')
    f.flush()
    while True:
        reply = json.loads(f.readline())
        if 'event' not in reply:
            break
print(json.dumps(reply))
sys.exit('error' in reply)
