import sys, re
path = sys.argv[1]; lo = int(sys.argv[2]); hi = int(sys.argv[3])
pat = re.compile(sys.argv[4])
infence = False
for i, line in enumerate(open(path, encoding="utf-8").read().split("\n"), 1):
    if line.lstrip().startswith("```"):
        if infence: print("%5d | [CODE %d-%d]" % (i, fo, i))
        else: fo = i
        infence = not infence; continue
    if infence or i < lo or i > hi: continue
    if pat.search(line): print("%5d | %s" % (i, line.rstrip()[:160]))
