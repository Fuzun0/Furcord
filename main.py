import json
from collections import defaultdict

import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect

app = FastAPI(title="WebRTC Signaling Server")

# { room_id: { client_id: WebSocket } }
rooms: dict[str, dict[str, WebSocket]] = defaultdict(dict)


async def broadcast(room_id: str, sender_id: str, message: dict) -> None:
    """Send a message to every client in the room except the sender."""
    disconnected: list[str] = []

    for client_id, ws in rooms[room_id].items():
        if client_id == sender_id:
            continue
        try:
            await ws.send_text(json.dumps(message))
        except Exception:
            disconnected.append(client_id)

    for client_id in disconnected:
        rooms[room_id].pop(client_id, None)


@app.websocket("/ws/{room_id}/{client_id}")
async def signaling_endpoint(websocket: WebSocket, room_id: str, client_id: str) -> None:
    await websocket.accept()

    rooms[room_id][client_id] = websocket
    print(f"[+] {client_id} joined room '{room_id}'  (peers: {list(rooms[room_id].keys())})")

    try:
        while True:
            raw = await websocket.receive_text()

            try:
                data = json.loads(raw)
            except json.JSONDecodeError:
                await websocket.send_text(json.dumps({"error": "Invalid JSON"}))
                continue

            msg_type = data.get("type")

            if msg_type not in ("offer", "answer", "ice-candidate"):
                await websocket.send_text(
                    json.dumps({"error": f"Unknown message type: {msg_type!r}"})
                )
                continue

            # Attach sender info and forward to room peers
            data["from"] = client_id
            await broadcast(room_id, client_id, data)

    except WebSocketDisconnect:
        rooms[room_id].pop(client_id, None)
        print(f"[-] {client_id} left room '{room_id}'  (peers: {list(rooms[room_id].keys())})")

        # Clean up empty rooms
        if not rooms[room_id]:
            del rooms[room_id]
            print(f"[*] Room '{room_id}' removed (empty)")


if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
