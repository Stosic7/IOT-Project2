using System.Globalization;
using System.Runtime.InteropServices;
using System.Text.Json;
using AnalyticsService;
using Confluent.Kafka;
using Microsoft.Extensions.Logging;

using var loggerFactory = LoggerFactory.Create(b => b
    .AddSimpleConsole(o => { o.TimestampFormat = "HH:mm:ss.fff "; o.SingleLine = true; })
    .SetMinimumLevel(LogLevel.Information));
var logger = loggerFactory.CreateLogger("Analytics");

string bootstrapServers = GetEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
string topic = GetEnv("KAFKA_TOPIC", "iot-sensors");
string consumerGroup = GetEnv("CONSUMER_GROUP", "analytics-group");
double tempThreshold = double.Parse(GetEnv("TEMP_ALERT_THRESHOLD", "50"), CultureInfo.InvariantCulture);
int windowSec = int.Parse(GetEnv("WINDOW_SEC", "10"));
int latencySampleEvery = int.Parse(GetEnv("LATENCY_SAMPLE_EVERY", "1"));

logger.LogInformation(
    "Analytics starting | bootstrap={Bootstrap} topic={Topic} group={Group} windowSec={Window} tempThreshold={Threshold}C latencySampleEvery={Sample}",
    bootstrapServers, topic, consumerGroup, windowSec, tempThreshold, latencySampleEvery);

var consumerConfig = new ConsumerConfig
{
    BootstrapServers = bootstrapServers,
    GroupId = consumerGroup,
    AutoOffsetReset = AutoOffsetReset.Earliest,
    EnableAutoCommit = true
};

using var consumer = new ConsumerBuilder<string, string>(consumerConfig).Build();
consumer.Subscribe(topic);

using var cts = new CancellationTokenSource();
PosixSignalRegistration.Create(PosixSignal.SIGTERM, ctx => { ctx.Cancel = true; cts.Cancel(); });
PosixSignalRegistration.Create(PosixSignal.SIGINT, ctx => { ctx.Cancel = true; cts.Cancel(); });

var tempBuffer = new List<double>();
var bufferLock = new object();
long msgCount = 0;

var consumeTask = Task.Run(() =>
{
    while (!cts.IsCancellationRequested)
    {
        ConsumeResult<string, string>? cr;
        try
        {
            cr = consumer.Consume(TimeSpan.FromMilliseconds(200));
        }
        catch (ConsumeException ex)
        {
            logger.LogWarning("Consume error: {Error}", ex.Error.Reason);
            continue;
        }

        if (cr == null || cr.IsPartitionEOF)
            continue;

        SensorReading? reading;
        try
        {
            reading = JsonSerializer.Deserialize<SensorReading>(cr.Message.Value);
        }
        catch (JsonException ex)
        {
            logger.LogWarning("Failed to deserialize message: {Error}", ex.Message);
            continue;
        }

        if (reading == null)
            continue;

        lock (bufferLock)
            tempBuffer.Add(reading.Temperature);

        long idx = Interlocked.Increment(ref msgCount);
        if (latencySampleEvery > 0 && idx % latencySampleEvery == 0)
        {
            double latencyMs = (DateTime.UtcNow - reading.SentAt).TotalMilliseconds;
            logger.LogInformation("[LATENCY] {DeviceId}: {LatencyMs:F1}ms end-to-end", reading.DeviceId, latencyMs);
        }
    }
});

using var windowTimer = new PeriodicTimer(TimeSpan.FromSeconds(windowSec));
try
{
    while (await windowTimer.WaitForNextTickAsync(cts.Token))
    {
        double[] snapshot;
        lock (bufferLock)
        {
            snapshot = tempBuffer.ToArray();
            tempBuffer.Clear();
        }

        if (snapshot.Length == 0)
            continue;

        double avg = snapshot.Average();
        logger.LogInformation("[WINDOW] count={Count} avgTemp={Avg:F2}C", snapshot.Length, avg);

        if (avg > tempThreshold)
            logger.LogCritical(
                "[ALERT] Prosecna temperatura u prozoru: {Avg:F2}C (n={Count}) - KRITICNO! prag={Threshold}C",
                avg, snapshot.Length, tempThreshold);
    }
}
catch (OperationCanceledException) { }

await consumeTask;
consumer.Close();
logger.LogInformation("Analytics stopped.");

// ---- local functions ----

string GetEnv(string key, string defaultValue) =>
    Environment.GetEnvironmentVariable(key) ?? defaultValue;
