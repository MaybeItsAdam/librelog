using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Dailylog.Sync;

/// The private Realtime Broadcast channel `user:<uid>` (SPEC.md §12.5), the
/// same Phoenix protocol as apple/App/Sync/RealtimeChannel.swift: join with
/// the user's access token, heartbeat every 25 s, rejoin after a drop.
/// `push_ops` sends an `ops` event on it, and this device pulls straight
/// away. It carries no journal data, only "something new".
public sealed class RealtimeChannel(string topic, SupabaseClient client, Action onPing) : IRealtime
{
    private readonly CancellationTokenSource stop = new();
    private int reference;

    public void Connect() => _ = Run(stop.Token);

    public void Dispose()
    {
        stop.Cancel();
        stop.Dispose();
    }

    private async Task Run(CancellationToken ct)
    {
        var delay = TimeSpan.FromSeconds(1);
        while (!ct.IsCancellationRequested)
        {
            try
            {
                using var ws = new ClientWebSocket();
                var token = await client.AccessToken(ct);
                await ws.ConnectAsync(SocketUri(), ct);
                await Send(ws, JoinMessage(topic, token, NextRef()), ct);
                delay = TimeSpan.FromSeconds(1);

                using var beat = CancellationTokenSource.CreateLinkedTokenSource(ct);
                var heartbeat = Heartbeat(ws, beat.Token);
                await Receive(ws, ct);
                beat.Cancel();
                await heartbeat.ContinueWith(_ => { }, CancellationToken.None);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                return;
            }
            catch (Exception e) when (e is WebSocketException or SyncException or HttpRequestException or OperationCanceledException)
            {
                // Dropped or refused: back off and rejoin.
            }
            try
            {
                await Task.Delay(delay, ct);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            delay = TimeSpan.FromSeconds(Math.Min(delay.TotalSeconds * 2, 60));
        }
    }

    internal static Uri SocketUri()
    {
        var b = new UriBuilder(new Uri(Backend.Url, "realtime/v1/websocket")) { Scheme = "wss", Port = -1 };
        b.Query = $"apikey={Uri.EscapeDataString(Backend.PublishableKey)}&vsn=1.0.0";
        return b.Uri;
    }

    internal static JsonObject JoinMessage(string topic, string token, string reference) => new()
    {
        ["topic"] = $"realtime:{topic}",
        ["event"] = "phx_join",
        ["payload"] = new JsonObject
        {
            ["config"] = new JsonObject
            {
                ["broadcast"] = new JsonObject { ["self"] = false },
                ["presence"] = new JsonObject { ["key"] = "" },
                ["private"] = true,
            },
            ["access_token"] = token,
        },
        ["ref"] = reference,
    };

    /// True for the broadcast `push_ops` sends.
    internal static bool IsOpsPing(string message)
    {
        try
        {
            var o = JsonNode.Parse(message) as JsonObject;
            return o?["event"]?.GetValue<string>() == "broadcast"
                && o["payload"]?["event"]?.GetValue<string>() == "ops";
        }
        catch (JsonException)
        {
            return false;
        }
    }

    private async Task Heartbeat(ClientWebSocket ws, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && ws.State == WebSocketState.Open)
        {
            await Task.Delay(TimeSpan.FromSeconds(25), ct);
            await Send(ws, new JsonObject
            {
                ["topic"] = "phoenix",
                ["event"] = "heartbeat",
                ["payload"] = new JsonObject(),
                ["ref"] = NextRef(),
            }, ct);
        }
    }

    private async Task Receive(ClientWebSocket ws, CancellationToken ct)
    {
        var buffer = new byte[16 * 1024];
        var message = new MemoryStream();
        while (ws.State == WebSocketState.Open && !ct.IsCancellationRequested)
        {
            var result = await ws.ReceiveAsync(buffer, ct);
            if (result.MessageType == WebSocketMessageType.Close) return;
            message.Write(buffer, 0, result.Count);
            if (!result.EndOfMessage) continue;
            var text = Encoding.UTF8.GetString(message.GetBuffer(), 0, (int)message.Length);
            message.SetLength(0);
            if (IsOpsPing(text)) onPing();
        }
    }

    private static Task Send(ClientWebSocket ws, JsonObject o, CancellationToken ct) =>
        ws.SendAsync(Encoding.UTF8.GetBytes(o.ToJsonString()), WebSocketMessageType.Text, true, ct);

    private string NextRef() => Interlocked.Increment(ref reference).ToString();
}
