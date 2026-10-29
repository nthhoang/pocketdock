using System;
using System.IO;
using System.Text;
using System.Threading;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace AudioProbe
{
    internal static class Program
    {
        private static StreamWriter report;

        private static void Line(string text)
        {
            Console.WriteLine(text);
            report.WriteLine(text);
        }

        private static int Main(string[] args)
        {
            int seconds = 10;
            if (args.Length > 0)
            {
                int parsed;
                if (int.TryParse(args[0], out parsed) && parsed > 0 && parsed <= 60) seconds = parsed;
            }

            Directory.CreateDirectory("ketqua");
            report = new StreamWriter(Path.Combine("ketqua", "ketqua-am-thanh.txt"), false, new UTF8Encoding(false));
            report.AutoFlush = true;
            try
            {
                return Run(seconds);
            }
            catch (Exception e)
            {
                Line("KET QUA: LOI - " + e);
                return 1;
            }
            finally
            {
                report.Dispose();
            }
        }

        private static double PeakOf(byte[] buffer, int count, WaveFormat format)
        {
            double peak = 0;
            if (format.Encoding == WaveFormatEncoding.IeeeFloat && format.BitsPerSample == 32)
            {
                for (int i = 0; i + 4 <= count; i += 4)
                {
                    peak = Math.Max(peak, Math.Abs((double)BitConverter.ToSingle(buffer, i)));
                }
            }
            else if (format.BitsPerSample == 16)
            {
                for (int i = 0; i + 2 <= count; i += 2)
                {
                    peak = Math.Max(peak, Math.Abs(BitConverter.ToInt16(buffer, i) / 32768.0));
                }
            }
            return peak;
        }

        private static int Run(int seconds)
        {
            Line("=== THU LAY AM THANH DANG PHAT CUA MAY (WASAPI loopback) ===");
            Line("He dieu hanh: " + Environment.OSVersion + ", " + (Environment.Is64BitOperatingSystem ? "64-bit" : "32-bit"));

            var enumerator = new MMDeviceEnumerator();
            MMDevice device;
            try
            {
                device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            }
            catch (Exception e)
            {
                Line("KET QUA: LOI - khong co thiet bi phat am thanh mac dinh. " + e.Message);
                return 1;
            }
            Line("Thiet bi phat mac dinh: " + device.FriendlyName);
            foreach (var d in enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
            {
                Line("  - " + d.FriendlyName + (d.ID == device.ID ? "  (mac dinh)" : ""));
            }

            long totalBytes = 0;
            int packets = 0;
            double peak = 0;
            object gate = new object();
            WaveFormat format = null;
            string wavPath = Path.Combine("ketqua", "loopback.wav");

            using (var capture = new WasapiLoopbackCapture(device))
            {
                format = capture.WaveFormat;
                Line("Dinh dang thu duoc: " + format.SampleRate + " Hz, " + format.Channels + " kenh, "
                     + format.BitsPerSample + " bit, " + format.Encoding);

                using (var writer = new WaveFileWriter(wavPath, format))
                {
                    var stopped = new ManualResetEvent(false);
                    capture.DataAvailable += (sender, e) =>
                    {
                        if (e.BytesRecorded <= 0) return;
                        lock (gate)
                        {
                            writer.Write(e.Buffer, 0, e.BytesRecorded);
                            packets++;
                            totalBytes += e.BytesRecorded;
                            peak = Math.Max(peak, PeakOf(e.Buffer, e.BytesRecorded, format));
                        }
                    };
                    capture.RecordingStopped += (sender, e) => stopped.Set();

                    Line("Dang thu " + seconds + " giay. HAY PHAT NHAC hoac video co tieng tren may nay NGAY BAY GIO.");
                    capture.StartRecording();
                    Thread.Sleep(seconds * 1000);
                    capture.StopRecording();
                    stopped.WaitOne(3000);
                }
            }

            double audioSeconds = totalBytes / (double)format.AverageBytesPerSecond;
            Line("Thu duoc " + audioSeconds.ToString("0.0") + " giay am thanh trong " + seconds + " giay, "
                 + packets + " goi du lieu (khoang " + (packets / (double)seconds).ToString("0") + " goi/giay)");
            if (peak > 0)
            {
                Line("Muc am lon nhat: " + (20 * Math.Log10(peak)).ToString("0.0") + " dB (0 dB la to nhat)");
            }

            if (totalBytes == 0)
            {
                Line("KET QUA: KHONG THU DUOC DU LIEU. Thuong do luc do may khong phat am thanh nao"
                     + " (loopback chi gui du lieu khi co tieng). Hay mo nhac/video roi chay lai.");
                return 2;
            }
            if (peak < 0.001)
            {
                Line("KET QUA: CHI THU DUOC IM LANG (duoi -60 dB). Kiem tra am luong, nut tat tieng, va"
                     + " thiet bi phat mac dinh co dung khong.");
                return 2;
            }
            Line("KET QUA: OK - thu duoc am thanh. Hay mo file ketqua\\loopback.wav len nghe thu de kiem tra.");
            return 0;
        }
    }
}
