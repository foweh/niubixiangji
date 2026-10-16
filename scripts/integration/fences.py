import sys
path = sys.argv[1]
want = [int(x) for x in sys.argv[2].split(",")]
lines = open(path, encoding="utf-8").read().split("\n")
for w in want:
    i = w - 1
    if not lines[i].lstrip().startswith("```"):
        print("### fence %d 不是围栏: %r" % (w, lines[i][:60])); continue
    j = i + 1
    body = []
    while j < len(lines) and not lines[j].lstrip().startswith("```"):
        body.append(lines[j]); j += 1
    print("### fence %d (lines %d-%d, %d body lines)" % (w, i+1, j+1, len(body)))
    for b in (body[:2] + ["..."] if len(body) > 4 else []) + (body[-2:] if len(body) > 2 else []):
        print("   | " + b[:120])
