using System.Runtime.InteropServices;
using System.Text.Json;
using Confluent.Kafka;
using Microsoft.Extensions.Logging;
using Npgsql;
using NpgsqlTypes;
using StorageService;

using var loggerFactory = LoggerFactory.Create(b => b
    .AddSimpleConsole(o => { o.TimestampFormat = "HH:mm:ss.fff "; o.SingleLine = true; })
    .SetMinimumLevel(LogLevel.Information));
var logger = loggerFactory.CreateLogger("Storage");

string bootstrapServers = GetEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
string topic = GetEnv("KAFKA_TOPIC", "iot-sensors");
string consumerGroup = GetEnv("CONSUMER_GROUP", "storage-group");
string connectionString = GetEnv("POSTGRES_CONNECTION_STRING", "Host=postgres-kafka;Port=5432;Database=iot_kafka;Username=iot;Password=iot");
int batchSize = int.Parse(GetEnv("BATCH_SIZE", "500"));
int flushIntervalMs = int.Parse(GetEnv("FLUSH_INTERVAL_MS", "2000"));
bool disableDbWrite = bool.Parse(GetEnv("DISABLE_DB_WRITE", "false"));

logger.LogInformation(
    "Storage starting | bootstrap={Bootstrap} topic={Topic} group={Group} batchSize={BatchSize} flushIntervalMs={FlushInterval} disableDbWrite={DisableDb}",
    bootstrapServers, topic, consumerGroup, batchSize, flushIntervalMs, disableDbWrite);

var consumerConfig = new ConsumerConfig
{
    BootstrapServers = bootstrapServers,
    GroupId = consumerGroup,
    AutoOffsetReset = AutoOffsetReset.Earliest,
    EnableAutoCommit = false
};

using var consumer = new ConsumerBuilder<string, string>(consumerConfig).Build();
consumer.Subscribe(topic);

using var cts = new CancellationTokenSource();
PosixSignalRegistration.Create(PosixSignal.SIGTERM, ctx => { ctx.Cancel = true; cts.Cancel(); });
PosixSignalRegistration.Create(PosixSignal.SIGINT, ctx => { ctx.Cancel = true; cts.Cancel(); });

var buffer = new List<SensorReading>(batchSize);
var lastFlush = DateTime.UtcNow;

try
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

        if (cr != null && !cr.IsPartitionEOF)
        {
            try
            {
                var reading = JsonSerializer.Deserialize<SensorReading>(cr.Message.Value);
                if (reading != null)
                    buffer.Add(reading);
            }
            catch (JsonException ex)
            {
                logger.LogWarning("Failed to deserialize message: {Error}", ex.Message);
            }
        }

        bool timeToFlush = (DateTime.UtcNow - lastFlush).TotalMilliseconds >= flushIntervalMs;
        if (buffer.Count >= batchSize || (buffer.Count > 0 && timeToFlush))
            await FlushAsync();
    }
}
catch (OperationCanceledException) { }

await FlushAsync();
consumer.Close();
logger.LogInformation("Storage stopped.");

// ---- local functions ----

string GetEnv(string key, string defaultValue) =>
    Environment.GetEnvironmentVariable(key) ?? defaultValue;

async Task FlushAsync()
{
    if (buffer.Count == 0)
    {
        lastFlush = DateTime.UtcNow;
        return;
    }

    int count = buffer.Count;
    if (!disableDbWrite)
        await BulkInsertAsync(buffer);

    consumer.Commit();
    logger.LogInformation("[BATCH] flushed {Count} readings (dbWrite={DbWrite})", count, !disableDbWrite);
    LogConsumerLag();

    buffer.Clear();
    lastFlush = DateTime.UtcNow;
}

async Task BulkInsertAsync(List<SensorReading> batch)
{
    await using var conn = new NpgsqlConnection(connectionString);
    await conn.OpenAsync();
    await using var writer = await conn.BeginBinaryImportAsync(
        "COPY sensor_readings (timestamp, device_id, temperature, humidity, pressure, light, sound, motion, battery, location, broker) FROM STDIN (FORMAT BINARY)");

    foreach (var r in batch)
    {
        await writer.StartRowAsync();
        await writer.WriteAsync(r.Timestamp, NpgsqlDbType.TimestampTz);
        await writer.WriteAsync(r.DeviceId, NpgsqlDbType.Varchar);
        await writer.WriteAsync(r.Temperature, NpgsqlDbType.Double);
        await writer.WriteAsync(r.Humidity, NpgsqlDbType.Double);
        await writer.WriteAsync(r.Pressure, NpgsqlDbType.Double);
        await writer.WriteAsync(r.Light, NpgsqlDbType.Integer);
        await writer.WriteAsync(r.Sound, NpgsqlDbType.Integer);
        await writer.WriteAsync((short)r.Motion, NpgsqlDbType.Smallint);
        await writer.WriteAsync(r.Battery, NpgsqlDbType.Double);
        await writer.WriteAsync(r.Location, NpgsqlDbType.Varchar);
        await writer.WriteAsync("kafka", NpgsqlDbType.Varchar);
    }

    await writer.CompleteAsync();
}

void LogConsumerLag()
{
    try
    {
        var assignment = consumer.Assignment;
        foreach (var tp in assignment)
        {
            var watermark = consumer.QueryWatermarkOffsets(tp, TimeSpan.FromSeconds(5));
            var committed = consumer.Committed(new List<TopicPartition> { tp }, TimeSpan.FromSeconds(5)).First();
            long committedOffset = committed.Offset.Value < 0 ? 0 : committed.Offset.Value;
            long lag = watermark.High.Value - committedOffset;
            logger.LogInformation("[LAG] partition={Partition} highWatermark={High} committed={Committed} lag={Lag}",
                tp.Partition.Value, watermark.High.Value, committedOffset, lag);
        }
    }
    catch (KafkaException ex)
    {
        logger.LogWarning("Could not query consumer lag: {Error}", ex.Message);
    }
}
