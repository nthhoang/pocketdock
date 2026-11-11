using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace PocketDock.AudioHelper
{
    /// <summary>
    /// Chuong trinh phu cua PocketDock: lay am thanh dang phat cua may (WASAPI loopback), doi sang
    /// 16-bit, 48 kHz, 2 kenh, va ghi ra stdout tung khuc 10 ms (1920 byte), deu dan theo dong ho.
    /// Khi may khong phat gi thi ghi khuc im lang, de ben nhan luon co nhip deu.
    /// Nhat ky (chu khong dau) di ra stderr. Thoat khi ben nhan dong ong dan.
    /// Tham so: --version, --probe (thu mo thiet bi mac dinh roi in ket qua), khong tham so = chay lien tuc.
    /// </summary>
    internal static class Program
    {
        private const int OutRate = 48000;
        private const int ChunkMs = 10;
        private const int ChunkBytes = 1920; // 10 ms, 16-bit, 2 kenh, 48 kHz

        private static string lastLogText = "";
        private static long lastLogTick;

        [DllImport("winmm.dll")]
        private static extern uint timeBeginPeriod(uint uMilliseconds);

        private static void Log(string text)
        {
            long now = Environment.TickCount;
            if (text == lastLogText && now - lastLogTick < 30000) return; // tranh lap lai moi giay
            lastLogText = text;
            lastLogTick = now;
            Console.Error.WriteLine(text);
        }

        private static int Main(string[] args)
        {
            if (args.Length > 0 && args[0] == "--version")
            {
                Console.WriteLine("PocketDock.AudioHelper 1.0");
                return 0;
            }
            if (args.Length > 0 && args[0] == "--probe")
            {
                return Probe();
            }
            return RunStreaming();
        }

        private static int Probe()
        {
            try
            {
                using (var enumerator = new MMDeviceEnumerator())
                {
                    MMDevice device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                    using (var session = new Session(device))
                    {
                        Console.WriteLine("OK thiet bi=" + session.DeviceName + "; dinh dang=" + session.FormatText);
                    }
                }
                return 0;
            }
            catch (Exception e)
            {
                Console.WriteLine("LOI " + e.Message);
                return 1;
            }
        }

        private static int RunStreaming()
        {
            timeBeginPeriod(1);
            Stream stdout = Console.OpenStandardOutput();
            byte[] chunk = new byte[ChunkBytes];
            Session session = null;
            Stopwatch clock = Stopwatch.StartNew();
            long nextMs = 0;
            try
            {
                using (var enumerator = new MMDeviceEnumerator())
                {
                    while (true)
                    {
                        session = Refresh(enumerator, session);
                        for (int i = 0; i < 100; i++) // khoang 1 giay moi lan kiem tra thiet bi
                        {
                            nextMs += ChunkMs;
                            WaitUntil(clock, nextMs);
                            if (clock.ElapsedMilliseconds - nextMs > 100)
                            {
                                nextMs = clock.ElapsedMilliseconds; // tre qua nhieu, dat lai nhip
                            }
                            if (session != null)
                            {
                                session.Read(chunk);
                            }
                            else
                            {
                                Array.Clear(chunk, 0, chunk.Length);
                            }
                            stdout.Write(chunk, 0, chunk.Length);
                            stdout.Flush();
                        }
                    }
                }
            }
            catch (IOException)
            {
                return 0; // ben nhan da dong ong dan
            }
            catch (Exception e)
            {
                Log("LOI khong xu ly duoc: " + e);
                return 1;
            }
            finally
            {
                if (session != null) session.Dispose();
            }
        }

        private static void WaitUntil(Stopwatch clock, long targetMs)
        {
            while (true)
            {
                long remain = targetMs - clock.ElapsedMilliseconds;
                if (remain <= 0) return;
                if (remain > 1) Thread.Sleep(1);
                else Thread.Yield();
            }
        }

        /// <summary>Giu phien thu hien tai neu thiet bi phat mac dinh khong doi; neu doi hoac loi thi mo lai.</summary>
        private static Session Refresh(MMDeviceEnumerator enumerator, Session session)
        {
            MMDevice device = null;
            try
            {
                device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            }
            catch (Exception e)
            {
                Log("Khong co thiet bi phat mac dinh: " + e.Message);
            }

            if (session != null)
            {
                bool same = device != null && device.ID == session.DeviceId;
                if (same && !session.Faulted)
                {
                    device.Dispose();
                    return session;
                }
                Log(session.Faulted ? "Thiet bi thu bi loi, mo lai" : "Doi thiet bi phat, mo lai");
                session.Dispose();
                session = null;
            }

            if (device == null) return null;
            try
            {
                Session created = new Session(device);
                Log("Dang thu tu: " + created.DeviceName + " (" + created.FormatText + ")");
                return created;
            }
            catch (Exception e)
            {
                Log("Khong mo duoc thiet bi thu: " + e.Message);
                return null;
            }
        }

        private sealed class Session : IDisposable
        {
            private readonly WasapiLoopbackCapture capture;
            private readonly BufferedWaveProvider buffer;
            private readonly IWaveProvider output;
            private readonly int needBytes;     // luong du lieu goc ung voi 10 ms
            private readonly int maxBufferedBytes;
            private volatile bool disposing;
            private bool primed;

            public volatile bool Faulted;
            public readonly string DeviceId;
            public readonly string DeviceName;
            public readonly string FormatText;

            public Session(MMDevice device)
            {
                DeviceId = device.ID;
                DeviceName = device.FriendlyName;
                capture = new WasapiLoopbackCapture(device);
                try
                {
                    WaveFormat f = capture.WaveFormat;
                    FormatText = f.SampleRate + " Hz, " + f.Channels + " kenh, " + f.BitsPerSample + " bit";

                    // Che do dung chung cua Windows luon tra so thuc 32 bit; cac dinh dang khac coi nhu PCM.
                    WaveFormat source = f.BitsPerSample == 32
                        ? WaveFormat.CreateIeeeFloatWaveFormat(f.SampleRate, f.Channels)
                        : new WaveFormat(f.SampleRate, f.BitsPerSample, f.Channels);

                    buffer = new BufferedWaveProvider(source);
                    buffer.ReadFully = true;
                    buffer.DiscardOnBufferOverflow = true;
                    buffer.BufferDuration = TimeSpan.FromMilliseconds(500);
                    needBytes = source.AverageBytesPerSecond * ChunkMs / 1000;
                    maxBufferedBytes = source.AverageBytesPerSecond / 5; // 200 ms

                    ISampleProvider samples = buffer.ToSampleProvider();
                    if (f.Channels == 1)
                    {
                        samples = new MonoToStereoSampleProvider(samples);
                    }
                    else if (f.Channels > 2)
                    {
                        var mux = new MultiplexingSampleProvider(new ISampleProvider[] { samples }, 2);
                        mux.ConnectInputToOutput(0, 0);
                        mux.ConnectInputToOutput(1, 1);
                        samples = mux;
                    }
                    if (f.SampleRate != OutRate)
                    {
                        samples = new WdlResamplingSampleProvider(samples, OutRate);
                    }
                    output = new SampleToWaveProvider16(samples);

                    capture.DataAvailable += OnData;
                    capture.RecordingStopped += OnStopped;
                    capture.StartRecording();
                }
                catch (Exception)
                {
                    capture.Dispose();
                    throw;
                }
            }

            private void OnData(object sender, WaveInEventArgs e)
            {
                if (e.BytesRecorded > 0) buffer.AddSamples(e.Buffer, 0, e.BytesRecorded);
            }

            private void OnStopped(object sender, StoppedEventArgs e)
            {
                if (!disposing) Faulted = true;
            }

            /// <summary>Doc dung mot khuc 10 ms. Dem it qua thi tra im lang va cho dem day lai (khoang 40 ms).</summary>
            public void Read(byte[] dest)
            {
                if (buffer.BufferedBytes > maxBufferedBytes) buffer.ClearBuffer(); // tre qua 200 ms thi bo bot

                if (!primed)
                {
                    if (buffer.BufferedBytes >= needBytes * 4)
                    {
                        primed = true;
                    }
                    else
                    {
                        Array.Clear(dest, 0, dest.Length);
                        return;
                    }
                }
                else if (buffer.BufferedBytes < needBytes)
                {
                    primed = false;
                    Array.Clear(dest, 0, dest.Length);
                    return;
                }

                int got = 0;
                while (got < dest.Length)
                {
                    int n = output.Read(dest, got, dest.Length - got);
                    if (n <= 0)
                    {
                        Array.Clear(dest, got, dest.Length - got);
                        break;
                    }
                    got += n;
                }
            }

            public void Dispose()
            {
                disposing = true;
                try
                {
                    capture.DataAvailable -= OnData;
                    capture.RecordingStopped -= OnStopped;
                    capture.StopRecording();
                }
                catch (Exception)
                {
                    // bo qua
                }
                try
                {
                    capture.Dispose();
                }
                catch (Exception)
                {
                    // bo qua
                }
            }
        }
    }
}
