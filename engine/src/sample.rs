//! Tiny valid sample files for the `fake` demo profile (so viewers can be tried without a server):
//! an uncompressed PNG, a one-page PDF and a one-second WAV tone. Generated, not embedded.

fn crc32(data: &[u8]) -> u32 {
    let mut c = 0xFFFF_FFFFu32;
    for &b in data {
        c ^= b as u32;
        for _ in 0..8 {
            c = if c & 1 != 0 { 0xEDB8_8320 ^ (c >> 1) } else { c >> 1 };
        }
    }
    !c
}

fn adler32(data: &[u8]) -> u32 {
    let (mut a, mut b) = (1u32, 0u32);
    for &x in data {
        a = (a + x as u32) % 65521;
        b = (b + a) % 65521;
    }
    (b << 16) | a
}

fn chunk(out: &mut Vec<u8>, kind: &[u8; 4], data: &[u8]) {
    out.extend_from_slice(&(data.len() as u32).to_be_bytes());
    let mut body = kind.to_vec();
    body.extend_from_slice(data);
    out.extend_from_slice(&body);
    out.extend_from_slice(&crc32(&body).to_be_bytes());
}

/// 96x64 RGB gradient, stored (uncompressed) deflate blocks.
pub fn png() -> Vec<u8> {
    let (w, h) = (96usize, 64usize);
    let mut raw = Vec::with_capacity(h * (1 + w * 3));
    for y in 0..h {
        raw.push(0); // filter: none
        for x in 0..w {
            raw.extend_from_slice(&[(x * 255 / (w - 1)) as u8, (y * 255 / (h - 1)) as u8, 160]);
        }
    }
    let mut z = vec![0x78, 0x01];
    let mut chunks = raw.chunks(65535).peekable();
    while let Some(c) = chunks.next() {
        z.push(if chunks.peek().is_none() { 1 } else { 0 });
        z.extend_from_slice(&(c.len() as u16).to_le_bytes());
        z.extend_from_slice(&(!(c.len() as u16)).to_le_bytes());
        z.extend_from_slice(c);
    }
    z.extend_from_slice(&adler32(&raw).to_be_bytes());
    let mut out = vec![0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];
    let mut ihdr = Vec::new();
    ihdr.extend_from_slice(&(w as u32).to_be_bytes());
    ihdr.extend_from_slice(&(h as u32).to_be_bytes());
    ihdr.extend_from_slice(&[8, 2, 0, 0, 0]); // 8-bit RGB
    chunk(&mut out, b"IHDR", &ihdr);
    chunk(&mut out, b"IDAT", &z);
    chunk(&mut out, b"IEND", &[]);
    out
}

/// One A4-ish page with a line of text (Helvetica), correct xref table.
pub fn pdf() -> Vec<u8> {
    let content = "BT /F1 24 Tf 72 700 Td (NextData sample PDF) Tj ET";
    let objs = [
        "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>".to_string(),
        format!("<< /Length {} >>\nstream\n{}\nendstream", content.len(), content),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_string(),
    ];
    let mut out = b"%PDF-1.4\n".to_vec();
    let mut offsets = vec![];
    for (i, o) in objs.iter().enumerate() {
        offsets.push(out.len());
        out.extend_from_slice(format!("{} 0 obj\n{}\nendobj\n", i + 1, o).as_bytes());
    }
    let xref = out.len();
    out.extend_from_slice(format!("xref\n0 {}\n0000000000 65535 f \n", objs.len() + 1).as_bytes());
    for o in offsets {
        out.extend_from_slice(format!("{o:010} 00000 n \n").as_bytes());
    }
    out.extend_from_slice(format!("trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{}\n%%EOF\n", objs.len() + 1, xref).as_bytes());
    out
}

/// 1 s, 8 kHz, mono, 16-bit PCM, 440 Hz.
pub fn wav() -> Vec<u8> {
    let rate = 8000u32;
    let n = rate as usize;
    let mut pcm = Vec::with_capacity(n * 2);
    for i in 0..n {
        let t = i as f64 / rate as f64;
        let v = (t * 440.0 * std::f64::consts::TAU).sin() * 0.4 * i16::MAX as f64;
        pcm.extend_from_slice(&(v as i16).to_le_bytes());
    }
    let mut out = b"RIFF".to_vec();
    out.extend_from_slice(&(36 + pcm.len() as u32).to_le_bytes());
    out.extend_from_slice(b"WAVEfmt ");
    out.extend_from_slice(&16u32.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes()); // PCM
    out.extend_from_slice(&1u16.to_le_bytes()); // mono
    out.extend_from_slice(&rate.to_le_bytes());
    out.extend_from_slice(&(rate * 2).to_le_bytes());
    out.extend_from_slice(&2u16.to_le_bytes());
    out.extend_from_slice(&16u16.to_le_bytes());
    out.extend_from_slice(b"data");
    out.extend_from_slice(&(pcm.len() as u32).to_le_bytes());
    out.extend_from_slice(&pcm);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn png_has_valid_structure() {
        let p = png();
        assert_eq!(&p[..8], &[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A]);
        // walk the chunks and check every CRC
        let mut o = 8;
        let mut kinds = vec![];
        while o < p.len() {
            let len = u32::from_be_bytes(p[o..o + 4].try_into().unwrap()) as usize;
            let body = &p[o + 4..o + 8 + len];
            let crc = u32::from_be_bytes(p[o + 8 + len..o + 12 + len].try_into().unwrap());
            assert_eq!(crc32(body), crc, "bad crc in {:?}", &body[..4]);
            kinds.push(String::from_utf8_lossy(&body[..4]).to_string());
            o += 12 + len;
        }
        assert_eq!(kinds, ["IHDR", "IDAT", "IEND"]);
    }

    #[test]
    fn crc_and_adler_known_values() {
        assert_eq!(crc32(b"123456789"), 0xCBF4_3926);
        assert_eq!(adler32(b"Wikipedia"), 0x11E6_0398);
    }

    #[test]
    fn pdf_xref_offsets_point_at_objects() {
        let p = pdf();
        let text = String::from_utf8_lossy(&p).to_string();
        let start: usize = text.rsplit("startxref\n").next().unwrap().lines().next().unwrap().parse().unwrap();
        assert!(text[start..].starts_with("xref\n"));
        for (i, line) in text[start..].lines().skip(3).take(5).enumerate() {
            let off: usize = line[..10].parse().unwrap();
            assert!(text[off..].starts_with(&format!("{} 0 obj", i + 1)), "object {} at {}", i + 1, off);
        }
    }

    #[test]
    fn wav_header_is_consistent() {
        let w = wav();
        assert_eq!(&w[..4], b"RIFF");
        assert_eq!(u32::from_le_bytes(w[4..8].try_into().unwrap()) as usize, w.len() - 8);
        assert_eq!(&w[36..40], b"data");
        assert_eq!(u32::from_le_bytes(w[40..44].try_into().unwrap()) as usize, w.len() - 44);
    }
}
