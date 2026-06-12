using System.Text.Json.Serialization;

namespace StorageService;

public class SensorReading
{
    [JsonPropertyName("timestamp")]
    public DateTime Timestamp { get; set; }

    [JsonPropertyName("device_id")]
    public string DeviceId { get; set; } = "";

    [JsonPropertyName("temperature")]
    public double Temperature { get; set; }

    [JsonPropertyName("humidity")]
    public double Humidity { get; set; }

    [JsonPropertyName("pressure")]
    public double Pressure { get; set; }

    [JsonPropertyName("light")]
    public int Light { get; set; }

    [JsonPropertyName("sound")]
    public int Sound { get; set; }

    [JsonPropertyName("motion")]
    public int Motion { get; set; }

    [JsonPropertyName("battery")]
    public double Battery { get; set; }

    [JsonPropertyName("location")]
    public string Location { get; set; } = "";

    [JsonPropertyName("sent_at")]
    public DateTime SentAt { get; set; }
}
