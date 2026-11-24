// Same-origin WebSocket endpoint: hands the connection to the room's Durable Object.
export async function onRequest({ request, env }) {
  if (request.headers.get("Upgrade") !== "websocket") {
    return new Response("Expected WebSocket", { status: 426 });
  }
  const room = (new URL(request.url).searchParams.get("room") || "").toUpperCase();
  if (!/^[A-Z0-9]{4,12}$/.test(room)) return new Response("Bad room code", { status: 400 });
  return env.ROOM.get(env.ROOM.idFromName(room)).fetch(request);
}
