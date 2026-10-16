import sys
path = sys.argv[1]
want = [int(x) for x in sys.argv[2].split(",")]
maxl = int(sys.argv[3]) if len(sys.argv) > 3 else 8
lines = open(path, encoding="utf-8").read().split("\n")
for w in want:
    i = w - 1
    if not lines[i].lstrip().startswith("```"):
        print("### fence %d NOT A FENCE: %r" % (w, lines[i][:60])); continue
    j = i + 1; body = []
    while j < len(lines) and not lines[j].lstrip().startswith("```"):
        body.append(lines[j]); j += 1
    print("### fence %d (%d lines)" % (w, len(body)))
    if len(body) <= maxl:
        for b in body: print("   |" + b[:150])
