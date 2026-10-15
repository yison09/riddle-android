#!/usr/bin/env python3
"""Equivalence check for DiaryView.eraseAt / splitStroke (the Java logic, mirrored).

Old behaviour: any hit deleted the WHOLE stroke from the data, so erasing one
dot removed a whole line from the page model (data/visual divergence).
New behaviour: only the touched run of points is dropped, the untouched runs
survive as separate strokes.
"""
import math


class Stroke:
    def __init__(self, pts, width=3.0):
        self.pts = list(pts)
        self.width = width

    def __repr__(self):
        return "Stroke(%d pts)" % len(self.pts)


def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def split_stroke(s, x, y, r):
    out, cur = [], None
    for p in s.pts:
        if dist(p, (x, y)) < r:
            cur = None
            continue
        if cur is None:
            cur = Stroke([], s.width)
            out.append(cur)
        cur.pts.append(p)
    return out


def erase_new(strokes, x, y, r):
    for si in range(len(strokes) - 1, -1, -1):
        s = strokes[si]
        if not any(dist(p, (x, y)) < r for p in s.pts):
            continue
        strokes.pop(si)
        strokes[si:si] = split_stroke(s, x, y, r)
    return strokes


def erase_old(strokes, x, y, r):
    for si in range(len(strokes) - 1, -1, -1):
        s = strokes[si]
        if any(dist(p, (x, y)) < r for p in s.pts):
            strokes.pop(si)
    return strokes


# a straight 100-point line from (0,0) to (99,0)
line = Stroke([(float(i), 0.0) for i in range(100)])
R = 10.0

print("=== 1. erase the middle of a long stroke ===")
s = erase_new([Stroke(line.pts)], 50, 0, R)
print("  new ->", s, "=> kept", sum(len(k.pts) for k in s), "of 100 pts")
print("  old ->", erase_old([Stroke(line.pts)], 50, 0, R), "=> kept 0 pts (whole line lost)")

print("=== 2. erase one end ===")
s = erase_new([Stroke(line.pts)], 0, 0, R)
print("  new ->", s, "=> kept", sum(len(k.pts) for k in s), "pts (tail survives)")

print("=== 3. erase everything (sweep along the line) ===")
s = erase_new([Stroke(line.pts)], 50, 0, 200)
print("  new ->", s, "=> empty:", len(s) == 0, "(hasInk recomputed from the bitmap)")

print("=== 4. two strokes, erase only the first ===")
a = Stroke([(float(i), 0.0) for i in range(20)])
b = Stroke([(float(i), 100.0) for i in range(20)])
s = erase_new([Stroke(a.pts), Stroke(b.pts)], 10, 0, R)
print("  new ->", s, "=> second stroke untouched:", any(k.pts[0][1] == 100.0 for k in s))

print("=== 5. erase between two strokes (no hit) ===")
s = erase_new([Stroke(a.pts), Stroke(b.pts)], 10, 50, R)
print("  new ->", s, "=> both intact:", len(s) == 2)
