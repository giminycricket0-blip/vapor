// Wire schema: SteamDatabase/Protobufs, steammessages_clientserver_2.proto.
// License decisions are made by Steam; a successful transport is not a grant.
use crate::proto_wire::{Reader, WireType, Writer};

pub fn request(app_id: u32) -> Vec<u8> {
    let mut out = Vec::new();
    Writer::new(&mut out).uint32_field(2, app_id);
    out
}

#[derive(Debug, PartialEq)]
pub struct FreeLicenseResponse {
    pub eresult: u32,
    pub package_ids: Vec<u32>,
    pub app_ids: Vec<u32>,
}

impl FreeLicenseResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut result = Self { eresult: 2, package_ids: vec![], app_ids: vec![] };
        while !reader.eof() {
            let tag = reader.next_tag()?;
            match (tag.field_number, tag.wire_type) {
                (1, WireType::Varint) => result.eresult = reader.u32()?,
                (2 | 3, wire) => {
                    let ids = if tag.field_number == 2 { &mut result.package_ids } else { &mut result.app_ids };
                    match wire {
                        WireType::Varint => ids.push(reader.u32()?),
                        WireType::LengthDelimited => {
                            let mut packed = Reader::new(reader.bytes()?);
                            while !packed.eof() { ids.push(packed.u32()?); }
                        }
                        _ => return None,
                    }
                }
                _ => if !reader.skip(tag.wire_type) { return None; },
            }
        }
        Some(result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn eu3_request_uses_app_field_two() {
        assert_eq!(request(22150), vec![0x10, 0x86, 0xad, 0x01]);
    }

    #[test]
    fn reads_granted_apps_and_packages_in_both_encodings() {
        let mut body = Vec::new();
        let mut w = Writer::new(&mut body);
        w.uint32_field(1, 1);
        w.uint32_field(2, 123);
        w.bytes_field(3, &[0x86, 0xad, 0x01]);
        w.uint32_field(3, 42);
        assert_eq!(FreeLicenseResponse::deserialize(&body), Some(FreeLicenseResponse {
            eresult: 1, package_ids: vec![123], app_ids: vec![22150, 42],
        }));
    }

    #[test]
    fn missing_result_and_malformed_response_cannot_grant_a_license() {
        assert_eq!(FreeLicenseResponse::deserialize(&[]).unwrap().eresult, 2);
        assert!(FreeLicenseResponse::deserialize(&[0x1a, 0x01, 0x80]).is_none());
        assert!(FreeLicenseResponse::deserialize(&[0x08]).is_none());
        assert_eq!(FreeLicenseResponse::deserialize(&[0x08, 0x0f]).unwrap().eresult, 15);
    }
}
