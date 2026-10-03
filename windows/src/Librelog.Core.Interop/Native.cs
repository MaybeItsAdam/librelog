using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization.Metadata;

namespace Librelog.Core;

/// Thrown for an `err` envelope. Kind is database | not_found | invalid |
/// sync | crypto | panic.
public sealed class CoreException(string kind, string message) : Exception(message)
{
    public string Kind { get; } = kind;
}

/// The five functions in core/src/capi.rs. The library is
/// librelog_core.dll on Windows (liblibrelog_core.dylib / .so elsewhere, which
/// is how the tests run on macOS and Linux).
internal static partial class Native
{
    private const string Lib = "librelog_core";

    [LibraryImport(Lib, EntryPoint = "dl_store_open", StringMarshalling = StringMarshalling.Utf8)]
    internal static partial IntPtr StoreOpen(string path);

    [LibraryImport(Lib, EntryPoint = "dl_store_close")]
    internal static partial void StoreClose(nuint handle);

    [LibraryImport(Lib, EntryPoint = "dl_store_call", StringMarshalling = StringMarshalling.Utf8)]
    internal static partial IntPtr StoreCall(nuint handle, string method, string args);

    [LibraryImport(Lib, EntryPoint = "dl_call", StringMarshalling = StringMarshalling.Utf8)]
    internal static partial IntPtr Call(string method, string args);

    [LibraryImport(Lib, EntryPoint = "dl_string_free")]
    internal static partial void StringFree(IntPtr s);

    /// Reads and frees a returned envelope, then unwraps it.
    internal static JsonElement Unwrap(IntPtr p)
    {
        string json;
        try
        {
            json = Marshal.PtrToStringUTF8(p) ?? throw new CoreException("panic", "null response");
        }
        finally
        {
            StringFree(p);
        }
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        if (root.TryGetProperty("err", out var err))
        {
            var body = err.Deserialize(CoreJson.Default.CoreErrorBody)!;
            throw new CoreException(body.Kind, body.Message);
        }
        return root.GetProperty("ok").Clone();
    }

    internal static T? As<T>(JsonElement e, JsonTypeInfo<T> type) =>
        e.ValueKind == JsonValueKind.Null ? default : e.Deserialize(type);

    internal static string Args(params (string Name, JsonNode? Value)[] args)
    {
        var o = new JsonObject();
        foreach (var (name, value) in args) o[name] = value;
        return o.ToJsonString();
    }

    internal static JsonNode? Node<T>(T value, JsonTypeInfo<T> type) => JsonSerializer.SerializeToNode(value, type);
}

/// Core functions that need no store.
public static class CoreFunctions
{
    public static Shortcut? MarkdownShortcut(string text) =>
        Native.As(Call("markdown_shortcut", ("text", text)), CoreJson.Default.Shortcut);

    public static string BlocksToMarkdown(IReadOnlyList<Block> blocks) =>
        Call("blocks_to_markdown", ("blocks", Native.Node(blocks.ToList(), CoreJson.Default.ListBlock))).GetString()!;

    public static uint BlocksWordCount(IReadOnlyList<Block> blocks) =>
        Call("blocks_word_count", ("blocks", Native.Node(blocks.ToList(), CoreJson.Default.ListBlock))).GetUInt32();

    public static PairingCode ParsePairingCode(string text) =>
        Native.As(Call("parse_pairing_qr", ("text", text)), CoreJson.Default.PairingCode)!;

    internal static JsonElement Call(string method, params (string, JsonNode?)[] args) =>
        Native.Unwrap(Native.Call(method, Native.Args(args)));
}

/// The new-device half of QR pairing (core/src/pairing.rs).
public sealed class PairingSession : IDisposable
{
    private nuint handle;

    public PairingSession()
    {
        handle = (nuint)CoreFunctions.Call("pairing_new").GetUInt64();
        Id = CoreFunctions.Call("pairing_id", ("handle", handle)).GetString()!;
        Code = CoreFunctions.Call("pairing_qr", ("handle", handle)).GetString()!;
    }

    /// Pass to pairing_open and pairing_take.
    public string Id { get; }

    /// What to show as a QR code, or for the other device to paste.
    public string Code { get; }

    /// Opens the existing device's answer; returns the journal key (base64).
    public string Receive(string payload) =>
        CoreFunctions.Call("pairing_receive", ("handle", handle), ("payload", payload)).GetString()!;

    public void Dispose()
    {
        if (handle != 0) CoreFunctions.Call("pairing_close", ("handle", handle));
        handle = 0;
    }
}
