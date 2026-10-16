import sys
path = sys.argv[1]
lo = int(sys.argv[2]) if len(sys.argv) > 2 else 1
hi = int(sys.argv[3]) if len(sys.argv) > 3 else 10**9
infence = False
for i, line in enumerate(open(path, encoding="utf-8").read().split("\n"), 1):
    if line.lstrip().startswith("```"):
        if not infence:
            fence_open = i
        else:
            print("%5d | [CODE %d-%d]" % (i, fence_open, i))
        infence = not infence
        continue
    if infence or i < lo or i > hi:
        continue
    s = line.rstrip()
    if s.strip():
        print("%5d | %s" % (i, s[:150]))
