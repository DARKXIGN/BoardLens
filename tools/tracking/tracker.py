"""Reference implementation of the board tracker (mirrors TrackingRegionDetector.kt).

Long-term anchor = the user's selection; frame-to-frame matching as fallback when the
slide content changes; a new anchor is promoted once the change persists; frames much
blurrier than recent ones are skipped.
"""
import cv2, numpy as np

class View:
    def __init__(self, kp, des, quad_small):
        self.kp, self.des, self.quad = kp, des, quad_small

class Tracker:
    def __init__(self, work=640, nfeat=1000, ratio=0.8, min_inliers=15, min_inlier_ratio=0.4,
                 margin=0.25, area_range=(0.6, 1.6), promote_after=3, sharp_ratio=0.6, max_skips=2):
        self.__dict__.update({k: v for k, v in locals().items() if k != 'self'})
        self.orb = cv2.ORB_create(nfeat); self.bf = cv2.BFMatcher(cv2.NORM_HAMMING)

    def _prep(self, bgr):
        self.s = min(1.0, self.work / max(bgr.shape[:2]))
        return cv2.cvtColor(cv2.resize(bgr, None, fx=self.s, fy=self.s, interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2GRAY)

    def _view(self, g, quad_small):
        c = quad_small.mean(0); qm = c + (quad_small - c) * (1 + self.margin)
        mask = np.zeros(g.shape, np.uint8); cv2.fillConvexPoly(mask, qm.astype(np.int32), 255)
        kp, des = self.orb.detectAndCompute(g, mask)
        return View(kp, des, quad_small.astype(np.float32))

    def set_reference(self, bgr, quad):
        g = self._prep(bgr); v = self._view(g, quad * self.s)
        self.anchor = self.prev = v; self.ref_area = cv2.contourArea(v.quad)
        self.quad_small = v.quad; self.incremental_streak = 0; self.sharp_hist = []; self.skips = 0

    def _match(self, ref, kp, des):
        min_inliers = self.min_inliers
        rkp, rdes = ref.kp, ref.des
        if des is None or rdes is None or len(kp) < 8: return None
        good = [m for m, n in (p for p in self.bf.knnMatch(rdes, des, k=2) if len(p) == 2) if m.distance < self.ratio * n.distance]
        if len(good) < min_inliers: return None
        src = np.float32([rkp[m.queryIdx].pt for m in good]); dst = np.float32([kp[m.trainIdx].pt for m in good])
        H, inl = cv2.findHomography(src, dst, cv2.RANSAC, 4.0)
        if H is None: return None
        n = int(inl.sum())
        if n < min_inliers or n < self.min_inlier_ratio * len(good): return None
        q = cv2.perspectiveTransform(ref.quad.reshape(-1, 1, 2), H).reshape(4, 2)
        a = cv2.contourArea(q)
        if not (self.area_range[0] < a / self.ref_area < self.area_range[1]) or not cv2.isContourConvex(q.astype(np.float32)):
            return None
        return q

    def track(self, bgr):
        """Returns (quad_full_res, status) with status in {'anchor', 'incremental', 'lost'}."""
        g = self._prep(bgr)
        # Sharpness gate: skip frames much blurrier than recent ones (bad for tracking and OCR alike).
        m = np.zeros(g.shape, np.uint8); cv2.fillConvexPoly(m, self.quad_small.astype(np.int32), 255)
        sharp = float(cv2.Laplacian(g, cv2.CV_32F)[m > 0].var())
        recent = float(np.median(self.sharp_hist)) if self.sharp_hist else sharp
        self.sharp_hist = (self.sharp_hist + [sharp])[-8:]
        if sharp < self.sharp_ratio * recent and self.skips < self.max_skips:
            self.skips += 1
            return self.quad_small / self.s, 'skipped'
        self.skips = 0
        kp, des = self.orb.detectAndCompute(g, None)
        q = self._match(self.anchor, kp, des)
        if q is not None:
            status = 'anchor'; self.incremental_streak = 0
        else:
            q = self._match(self.prev, kp, des)
            if q is not None:
                status = 'incremental'; self.incremental_streak += 1
            else:
                status = 'lost'; q = self.quad_small   # hold; next frame matches against this one
        self.quad_small = q.astype(np.float32)
        # Features of this frame inside the (new) quad become the frame-to-frame reference.
        self.prev = self._view(g, self.quad_small)
        if self.incremental_streak >= self.promote_after:
            # The anchor has failed for a while but tracking holds: content changed (new slide).
            self.anchor = self.prev; self.incremental_streak = 0
        return self.quad_small / self.s, status
