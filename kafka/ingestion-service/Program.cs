using System.Globalization;
using System.Runtime.InteropServices;
using System.Text.Json;
using Confluent.Kafka;
using IngestionService;
using Microsoft.Extensions.Logging;

using var loggerFactory = LoggerFactory.Create(b => b
    .AddSimpleConsole(o => { o.TimestampFormat = "HH:mm:ss.fff "; o.SingleLine = true; })
    .SetMinimumLevel(LogLevel.Information));
var logger = loggerFactory.CreateLogger("Ingestion");

string bootstrapServers = GetEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
string topic = GetEnv("KAFKA_TOPIC", "iot-sensors");
string acksRaw = GetEnv("KAFKA_ACKS", "1");
int deviceCount = int.Parse(GetEnv("DEVICE_COUNT", "100"));
int maxProducerThreads = int.Parse(GetEnv("MAX_PRODUCER_THREADS", "64"));
int runDurationSec = int.Parse(GetEnv("RUN_DURATION_SEC", "60"));
int ratePerDeviceMs = int.Parse(GetEnv("RATE_PER_DEVICE_MS", "0"));
string runMode = GetEnv("RUN_MODE", "normal");
bool injectAlerts = bool.Parse(GetEnv("INJECT_ALERTS", "false"));
double alertProbability = double.Parse(GetEnv("ALERT_PROBABILITY", "0.05"), CultureInfo.InvariantCulture);
string csvPath = GetEnv("CSV_PATH", "/data/real_time_data.csv");
int burstBaseRate = int.Parse(GetEnv("BURST_BASE_RATE", "50"));
int burstPeakRate = int.Parse(GetEnv("BURST_PEAK_RATE", "5000"));
int burstPeakSec = int.Parse(GetEnv("BURST_PEAK_SEC", "5"));
int burstBaseSec = int.Parse(GetEnv("BURST_BASE_SEC", "10"));

var acks = acksRaw.Trim().ToLowerInvariant() switch
{
    "0" => Acks.None,
    "1" => Acks.Leader,
    "all" => Acks.All,
    _ => Acks.Leader
};

logger.LogInformation(
    "Ingestion starting | bootstrap={Bootstrap} topic={Topic} acks={Acks} deviceCount={DeviceCount} runMode={RunMode} durationSec={Duration} ratePerDeviceMs={Rate}",
    bootstrapServers, topic, acksRaw, deviceCount, runMode, runDurationSec, ratePerDeviceMs);

var rows = LoadCsv(csvPath);
logger.LogInformation("Loaded {Count} rows from {Path}", rows.Count, csvPath);

var producerConfig = new ProducerConfig
{
    BootstrapServers = bootstrapServers,
    Acks = acks,
    ClientId = "kafka-ingestion",
    QueueBufferingMaxMessages = 1_000_000,
    LingerMs = 5
};

using var producer = new ProducerBuilder<string, string>(producerConfig).Build();

long sentCount = 0;
long failedCount = 0;

void DeliveryHandler(DeliveryReport<string, string> report)
{
    if (report.Error.Code != ErrorCode.NoError)
        Interlocked.Increment(ref failedCount);
    else
        Interlocked.Increment(ref sentCount);
}

using var cts = new CancellationTokenSource();
if (runDurationSec > 0)
    cts.CancelAfter(TimeSpan.FromSeconds(runDurationSec));

PosixSignalRegistration.Create(PosixSignal.SIGTERM, ctx => { ctx.Cancel = true; cts.Cancel(); });
PosixSignalRegistration.Create(PosixSignal.SIGINT, ctx => { ctx.Cancel = true; cts.Cancel(); });

var pollTask = Task.Run(() =>
{
    while (!cts.IsCancellationRequested)
        producer.Poll(TimeSpan.FromMilliseconds(100));
});

var sw = System.Diagnostics.Stopwatch.StartNew();

try
{
    if (runMode == "burst")
        await RunBurstAsync(cts.Token);
    else
        await RunNormalAsync(cts.Token);
}
catch (OperationCanceledException) { }

sw.Stop();
cts.Cancel(); // ensure the background poll task stops even if the run finished on its own (e.g. burst phases)
producer.Flush(TimeSpan.FromSeconds(10));
await pollTask;

double elapsedSec = sw.Elapsed.TotalSeconds;
long sent = Interlocked.Read(ref sentCount);
long failed = Interlocked.Read(ref failedCount);
long total = sent + failed;
double lossPct = total == 0 ? 0 : 100.0 * failed / total;
double throughput = elapsedSec == 0 ? 0 : sent / elapsedSec;

logger.LogInformation(
    "[SUMMARY] sent={Sent} failed={Failed} lossPct={LossPct:F3}% elapsedSec={Elapsed:F1} throughput={Throughput:F1} msg/s",
    sent, failed, lossPct, elapsedSec, throughput);

// ---- local functions ----

string GetEnv(string key, string defaultValue) =>
    Environment.GetEnvironmentVariable(key) ?? defaultValue;

List<SensorReading> LoadCsv(string path)
{
    var list = new List<SensorReading>();
    using var reader = new StreamReader(path);
    reader.ReadLine(); // header
    string? line;
    while ((line = reader.ReadLine()) != null)
    {
        if (string.IsNullOrWhiteSpace(line)) continue;
        var p = line.Split(',');
        list.Add(new SensorReading
        {
            Timestamp = DateTime.Parse(p[0], CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal | DateTimeStyles.AssumeUniversal),
            DeviceId = p[1],
            Temperature = double.Parse(p[2], CultureInfo.InvariantCulture),
            Humidity = double.Parse(p[3], CultureInfo.InvariantCulture),
            Pressure = double.Parse(p[4], CultureInfo.InvariantCulture),
            Light = int.Parse(p[5], CultureInfo.InvariantCulture),
            Sound = int.Parse(p[6], CultureInfo.InvariantCulture),
            Motion = int.Parse(p[7], CultureInfo.InvariantCulture),
            Battery = double.Parse(p[8], CultureInfo.InvariantCulture),
            Location = p[9]
        });
    }
    return list;
}

void Send(string deviceId, SensorReading template)
{
    var now = DateTime.UtcNow;
    var reading = new SensorReading
    {
        Timestamp = now,
        DeviceId = deviceId,
        Temperature = template.Temperature,
        Humidity = template.Humidity,
        Pressure = template.Pressure,
        Light = template.Light,
        Sound = template.Sound,
        Motion = template.Motion,
        Battery = template.Battery,
        Location = template.Location,
        SentAt = now
    };

    if (injectAlerts && Random.Shared.NextDouble() < alertProbability)
        reading.Temperature = 55 + Random.Shared.NextDouble() * 5;

    var json = JsonSerializer.Serialize(reading);
    try
    {
        producer.Produce(topic, new Message<string, string> { Key = deviceId, Value = json }, DeliveryHandler);
    }
    catch (KafkaException)
    {
        Interlocked.Increment(ref failedCount);
    }
}

async Task RunNormalAsync(CancellationToken token)
{
    if (ratePerDeviceMs > 0)
    {
        // One lightweight task per simulated device, paced at a fixed rate.
        var tasks = new List<Task>(deviceCount);
        for (int d = 0; d < deviceCount; d++)
        {
            int deviceIndex = d;
            tasks.Add(Task.Run(async () =>
            {
                string deviceId = $"Device_{deviceIndex}";
                int rowCursor = deviceIndex % rows.Count;
                try
                {
                    while (!token.IsCancellationRequested)
                    {
                        Send(deviceId, rows[rowCursor]);
                        rowCursor = (rowCursor + 1) % rows.Count;
                        await Task.Delay(ratePerDeviceMs, token);
                    }
                }
                catch (OperationCanceledException) { }
            }));
        }
        await Task.WhenAll(tasks);
    }
    else
    {
        // Bounded tight-loop workers cycling through all device IDs for max throughput.
        int workerCount = Math.Max(1, Math.Min(deviceCount, maxProducerThreads));
        var tasks = new List<Task>(workerCount);
        for (int w = 0; w < workerCount; w++)
        {
            int workerId = w;
            tasks.Add(Task.Run(async () =>
            {
                int idx = workerId;
                int rowCursor = workerId % rows.Count;
                try
                {
                    while (!token.IsCancellationRequested)
                    {
                        Send($"Device_{idx % deviceCount}", rows[rowCursor]);
                        idx += workerCount;
                        rowCursor = (rowCursor + 1) % rows.Count;
                        if (idx % 1000 < workerCount)
                            await Task.Yield();
                    }
                }
                catch (OperationCanceledException) { }
            }));
        }
        await Task.WhenAll(tasks);
    }
}

async Task RunBurstAsync(CancellationToken token)
{
    var phases = new (int Rate, int DurationSec)[]
    {
        (burstBaseRate, burstBaseSec),
        (burstPeakRate, burstPeakSec),
        (burstBaseRate, burstBaseSec)
    };

    const int tickMs = 10;
    int deviceIdx = 0;
    int rowIdx = 0;

    foreach (var phase in phases)
    {
        logger.LogInformation("[BURST] phase rate={Rate} msg/s duration={Duration}s", phase.Rate, phase.DurationSec);
        var phaseEnd = DateTime.UtcNow.AddSeconds(phase.DurationSec);
        double msgsPerTick = phase.Rate * (tickMs / 1000.0);
        double accumulator = 0;

        while (DateTime.UtcNow < phaseEnd && !token.IsCancellationRequested)
        {
            accumulator += msgsPerTick;
            int toSend = (int)accumulator;
            accumulator -= toSend;

            for (int k = 0; k < toSend; k++)
            {
                Send($"Device_{deviceIdx % deviceCount}", rows[rowIdx % rows.Count]);
                deviceIdx++;
                rowIdx++;
            }

            await Task.Delay(tickMs, token);
        }
    }
}
