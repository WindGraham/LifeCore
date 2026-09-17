/**
 * LifecoreIndex — client-side root redirect for /console.
 *
 * Replaces hermes' default `RootRedirect` (which forwards to /sessions).
 * When this page is mounted, the user is at the dashboard root in
 * `/console/` mode; we check the pair store:
 *   - no pair → redirect to /lc-pair
 *   - paired   → redirect to /lc-today
 *
 * Renders nothing while we decide. Note: this runs inside the same SPA
 * bundle, with BrowserRouter basename=/console (when reverse-proxied), so
 * the navigate("/lc-pair") becomes window.location.pathname = "/console/lc-pair".
 */
import { useEffect } from "react";
import { useNavigate } from "react-router";
import { getPair } from "@/lib/lifecore-pair-store";

export default function LifecoreIndex() {
  const navigate = useNavigate();

  useEffect(() => {
    const pair = getPair();
    if (pair) {
      navigate("/lc-today", { replace: true });
    } else {
      navigate("/lc-pair", { replace: true });
    }
  }, [navigate]);

  return null;
}
