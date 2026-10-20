using System.Text.Json;
using System.Text.Json.Serialization;

namespace Librelog.Core;

/// Source-generated JSON for every type that crosses the C ABI, so nothing
/// relies on reflection and the app can publish with NativeAOT.
[JsonSourceGenerationOptions(
    PropertyNamingPolicy = JsonKnownNamingPolicy.SnakeCaseLower,
    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull)]
[JsonSerializable(typeof(Block))]
[JsonSerializable(typeof(List<Block>))]
[JsonSerializable(typeof(Journal))]
[JsonSerializable(typeof(List<Journal>))]
[JsonSerializable(typeof(Entry))]
[JsonSerializable(typeof(List<EntrySummary>))]
[JsonSerializable(typeof(List<DayMark>))]
[JsonSerializable(typeof(List<SearchHit>))]
[JsonSerializable(typeof(Stats))]
[JsonSerializable(typeof(Tag))]
[JsonSerializable(typeof(List<Tag>))]
[JsonSerializable(typeof(TimelineFilter))]
[JsonSerializable(typeof(TimelineCursor))]
[JsonSerializable(typeof(OutgoingBatch))]
[JsonSerializable(typeof(OutgoingSnapshot))]
[JsonSerializable(typeof(List<IncomingOps>))]
[JsonSerializable(typeof(PullResult))]
[JsonSerializable(typeof(List<Revision>))]
[JsonSerializable(typeof(Shortcut))]
[JsonSerializable(typeof(PairingCode))]
[JsonSerializable(typeof(CoreErrorBody))]
[JsonSerializable(typeof(NewPhoto))]
[JsonSerializable(typeof(DayPhoto))]
[JsonSerializable(typeof(List<DayPhoto>))]
[JsonSerializable(typeof(ThumbImage))]
[JsonSerializable(typeof(KeptEvent))]
[JsonSerializable(typeof(List<KeptEvent>))]
[JsonSerializable(typeof(OwnEvent))]
[JsonSerializable(typeof(LockStatus))]
[JsonSerializable(typeof(ExportSummary))]
[JsonSerializable(typeof(ImportPreview))]
[JsonSerializable(typeof(ImportResult))]
[JsonSerializable(typeof(string))]
[JsonSerializable(typeof(bool))]
[JsonSerializable(typeof(uint))]
[JsonSerializable(typeof(long))]
[JsonSerializable(typeof(ulong))]
public sealed partial class CoreJson : JsonSerializerContext;
