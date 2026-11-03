const ORIGIN_KEY = "quick_tunnel_origin";
const ORIGIN_PATTERN = /^https:\/\/[a-z0-9-]+\.trycloudflare\.com$/;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/__control/origin") {
      if (request.method !== "POST") return new Response("Method not allowed", { status: 405 });
      const supplied = request.headers.get("Authorization") || "";
      if (!env.CONTROL_SECRET || supplied !== `Bearer ${env.CONTROL_SECRET}`) {
        return new Response("Unauthorized", { status: 401 });
      }
      let body;
      try { body = await request.json(); } catch { return new Response("Invalid JSON", { status: 400 }); }
      const origin = typeof body.origin === "string" ? body.origin.replace(/\/$/, "") : "";
      if (origin && !ORIGIN_PATTERN.test(origin)) return new Response("Origin must be a Cloudflare Quick Tunnel HTTPS URL", { status: 400 });
      if (!origin) await env.CONFIG.delete(ORIGIN_KEY);
      else await env.CONFIG.put(ORIGIN_KEY, origin);
      return Response.json({ ok: true, online: Boolean(origin) }, { headers: { "Cache-Control": "no-store" } });
    }

    const origin = await env.CONFIG.get(ORIGIN_KEY);
    if (url.pathname === "/__status") {
      if (!origin) return Response.json({ online: false }, { status: 503, headers: { "Cache-Control": "no-store" } });
      try {
        const upstream = await fetch(`${origin}/latest.json`, { signal: AbortSignal.timeout(8000) });
        return Response.json({ online: upstream.ok }, { status: upstream.ok ? 200 : 503, headers: { "Cache-Control": "no-store" } });
      } catch {
        return Response.json({ online: false }, { status: 503, headers: { "Cache-Control": "no-store" } });
      }
    }

    if (request.method !== "GET" && request.method !== "HEAD") return new Response("Method not allowed", { status: 405 });
    if (!origin) return new Response("Update server is offline", { status: 503, headers: { "Cache-Control": "no-store", "Retry-After": "30" } });
    try {
      const target = new URL(url.pathname + url.search, `${origin}/`);
      const upstream = await fetch(new Request(target, request));
      const headers = new Headers(upstream.headers);
      headers.set("Cache-Control", "no-store");
      headers.set("X-Content-Type-Options", "nosniff");
      return new Response(upstream.body, { status: upstream.status, statusText: upstream.statusText, headers });
    } catch {
      return new Response("Update server is temporarily unavailable", { status: 503, headers: { "Cache-Control": "no-store", "Retry-After": "30" } });
    }
  }
};
