import { DurableObject } from "cloudflare:workers";

// One Room per projector (named by its room code).
// Tags: "device" = the projector app, "remote" = browser controllers.
//   remote -> device : forwarded verbatim (pointer / settings messages)
//   device -> remotes: forwarded verbatim (config broadcasts)
//   relay  -> device : "n<count>" connected controllers
//   relay  -> remote : "d1" / "d0" projector online / offline
// "k" is a keepalive answered by the runtime without waking the object.
export class Room extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("k", "k"));
  }

  async fetch(request) {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Expected WebSocket", { status: 426 });
    }
    const role = new URL(request.url).searchParams.get("role") === "device" ? "device" : "remote";
    const { 0: client, 1: server } = new WebSocketPair();

    if (role === "device") {
      for (const old of this.ctx.getWebSockets("device")) {
        try { old.close(4000, "Replaced by a newer projector connection"); } catch {}
      }
    }
    this.ctx.acceptWebSocket(server, [role]);

    if (role === "device") {
      server.send("n" + this.ctx.getWebSockets("remote").length);
      this.broadcast("remote", "d1");
    } else {
      server.send(this.ctx.getWebSockets("device").length ? "d1" : "d0");
      this.broadcast("device", "n" + this.ctx.getWebSockets("remote").length);
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  webSocketMessage(ws, message) {
    if (typeof message !== "string" || message.length > 4096) return;
    const role = this.ctx.getTags(ws)[0];
    this.broadcast(role === "device" ? "remote" : "device", message);
  }

  webSocketClose(ws, code) {
    try { ws.close(code === 1005 ? 1000 : code, "closing"); } catch {}
    this.left(ws);
  }

  webSocketError(ws) {
    this.left(ws);
  }

  left(ws) {
    const role = this.ctx.getTags(ws)[0];
    if (role === "device") {
      if (!this.ctx.getWebSockets("device").some((s) => s !== ws)) this.broadcast("remote", "d0");
    } else {
      const n = this.ctx.getWebSockets("remote").filter((s) => s !== ws).length;
      this.broadcast("device", "n" + n);
    }
  }

  broadcast(tag, message) {
    for (const s of this.ctx.getWebSockets(tag)) {
      try { s.send(message); } catch {}
    }
  }
}

export function roomStub(env, request) {
  const room = (new URL(request.url).searchParams.get("room") || "").toUpperCase();
  if (!/^[A-Z0-9]{4,12}$/.test(room)) return null;
  return env.ROOM.get(env.ROOM.idFromName(room));
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/ws") {
      const stub = roomStub(env, request);
      if (!stub) return new Response("Bad room code", { status: 400 });
      return stub.fetch(request);
    }
    return new Response("thecatgame relay", { status: 200 });
  },
};
