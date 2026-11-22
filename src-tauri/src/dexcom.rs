use reqwest::Client;
use serde::{de, Deserialize, Deserializer, Serialize};
use std::sync::Mutex;

const APP_ID_STANDARD: &str = "d89443d2-327c-4a6f-89e5-496bbb0317db";
const APP_ID_JP: &str = "d8665ade-9673-4e27-9ff6-92db4ce13d13";
const EMPTY_UUID: &str = "00000000-0000-0000-0000-000000000000";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum TrendDirection {
    None,
    DoubleUp,
    SingleUp,
    FortyFiveUp,
    Flat,
    FortyFiveDown,
    SingleDown,
    DoubleDown,
    NotComputable,
    RateOutOfRange,
    Unknown,
}

impl TrendDirection {
    fn from_code(code: i64) -> Self {
        match code {
            0 => Self::None,
            1 => Self::DoubleUp,
            2 => Self::SingleUp,
            3 => Self::FortyFiveUp,
            4 => Self::Flat,
            5 => Self::FortyFiveDown,
            6 => Self::SingleDown,
            7 => Self::DoubleDown,
            8 => Self::NotComputable,
            9 => Self::RateOutOfRange,
            _ => Self::Unknown,
        }
    }

    fn from_label(label: &str) -> Self {
        match label.trim().to_ascii_lowercase().as_str() {
            "none" => Self::None,
            "doubleup" => Self::DoubleUp,
            "singleup" => Self::SingleUp,
            "fortyfiveup" => Self::FortyFiveUp,
            "flat" => Self::Flat,
            "fortyfivedown" => Self::FortyFiveDown,
            "singledown" => Self::SingleDown,
            "doubledown" => Self::DoubleDown,
            "notcomputable" => Self::NotComputable,
            "rateoutofrange" => Self::RateOutOfRange,
            _ => Self::Unknown,
        }
    }
}

fn deserialize_trend<'de, D>(deserializer: D) -> Result<TrendDirection, D::Error>
where
    D: Deserializer<'de>,
{
    struct TrendVisitor;

    impl<'de> de::Visitor<'de> for TrendVisitor {
        type Value = TrendDirection;

        fn expecting(&self, formatter: &mut std::fmt::Formatter) -> std::fmt::Result {
            formatter.write_str("a Dexcom trend string or integer code")
        }

        fn visit_i64<E>(self, value: i64) -> Result<Self::Value, E> {
            Ok(TrendDirection::from_code(value))
        }

        fn visit_u64<E>(self, value: u64) -> Result<Self::Value, E> {
            Ok(TrendDirection::from_code(value as i64))
        }

        fn visit_str<E>(self, value: &str) -> Result<Self::Value, E> {
            if let Ok(code) = value.trim().parse::<i64>() {
                return Ok(TrendDirection::from_code(code));
            }
            Ok(TrendDirection::from_label(value))
        }
    }

    deserializer.deserialize_any(TrendVisitor)
}

#[derive(Debug, Clone, Deserialize)]
struct RawDexcomReading {
    #[serde(rename = "WT")]
    wt: String,
    #[serde(rename = "Value")]
    value: i32,
    #[serde(rename = "Trend", deserialize_with = "deserialize_trend")]
    trend: TrendDirection,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GlucoseReading {
    pub value: i32,
    pub trend: TrendDirection,
    pub timestamp: i64,
}

#[derive(Debug, Clone)]
struct CachedSession {
    region: String,
    username: String,
    session_id: String,
}

pub struct DexcomClient {
    http: Client,
    session: Mutex<Option<CachedSession>>,
}

impl DexcomClient {
    pub fn new() -> Self {
        Self {
            http: Client::new(),
            session: Mutex::new(None),
        }
    }

    pub fn clear_session(&self) {
        if let Ok(mut guard) = self.session.lock() {
            *guard = None;
        }
    }

    fn endpoint_config(region: &str) -> (&'static str, &'static str) {
        match region.to_ascii_lowercase().as_str() {
            "ous" => (
                "https://shareous1.dexcom.com/ShareWebServices/Services",
                APP_ID_STANDARD,
            ),
            "jp" => (
                "https://share.dexcom.jp/ShareWebServices/Services",
                APP_ID_JP,
            ),
            _ => (
                "https://share2.dexcom.com/ShareWebServices/Services",
                APP_ID_STANDARD,
            ),
        }
    }

    async fn authenticate(
        &self,
        region: &str,
        username: &str,
        password: &str,
    ) -> Result<String, String> {
        if username.trim().is_empty() || password.is_empty() {
            return Err("Dexcom username and password are required.".to_string());
        }

        let (base_url, app_id) = Self::endpoint_config(region);

        let auth_url = format!("{base_url}/General/AuthenticatePublisherAccount");
        let auth_body = serde_json::json!({
            "accountName": username.trim(),
            "password": password,
            "applicationId": app_id,
        });

        let auth_resp = self
            .http
            .post(&auth_url)
            .header("Accept", "application/json")
            .json(&auth_body)
            .send()
            .await
            .map_err(|e| format!("Network error connecting to Dexcom Share: {e}"))?;

        if !auth_resp.status().is_success() {
            let status = auth_resp.status();
            let text = auth_resp.text().await.unwrap_or_default();
            return Err(format!("Dexcom authentication failed ({status}): {text}"));
        }

        let account_id: String = auth_resp
            .json()
            .await
            .map_err(|e| format!("Invalid Dexcom account ID response: {e}"))?;

        if account_id.is_empty() || account_id == EMPTY_UUID {
            return Err("Invalid Dexcom credentials or wrong region.".to_string());
        }

        let login_url = format!("{base_url}/General/LoginPublisherAccountById");
        let login_body = serde_json::json!({
            "accountId": account_id,
            "password": password,
            "applicationId": app_id,
        });

        let login_resp = self
            .http
            .post(&login_url)
            .header("Accept", "application/json")
            .json(&login_body)
            .send()
            .await
            .map_err(|e| format!("Network error during Dexcom login: {e}"))?;

        if !login_resp.status().is_success() {
            let status = login_resp.status();
            let text = login_resp.text().await.unwrap_or_default();
            return Err(format!("Dexcom session login failed ({status}): {text}"));
        }

        let session_id: String = login_resp
            .json()
            .await
            .map_err(|e| format!("Invalid Dexcom session ID response: {e}"))?;

        if session_id.is_empty() || session_id == EMPTY_UUID {
            return Err("Dexcom login returned an empty session ID.".to_string());
        }

        Ok(session_id)
    }

    async fn read_values_with_session(
        &self,
        region: &str,
        session_id: &str,
        minutes: u32,
        max_count: u32,
    ) -> Result<Vec<GlucoseReading>, (bool, String)> {
        let (base_url, _) = Self::endpoint_config(region);
        let url = format!(
            "{base_url}/Publisher/ReadPublisherLatestGlucoseValues?sessionId={session_id}&minutes={minutes}&maxCount={max_count}"
        );

        let resp = self
            .http
            .post(&url)
            .header("Accept", "application/json")
            .header("Content-Length", "0")
            .send()
            .await
            .map_err(|e| (false, format!("Network error reading Dexcom values: {e}")))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            let session_expired = body.contains("SessionIdNotFound")
                || body.contains("SessionNotValid")
                || status.as_u16() == 500;
            return Err((
                session_expired,
                format!("Dexcom read error ({status}): {body}"),
            ));
        }

        let raw: Vec<RawDexcomReading> = resp
            .json()
            .await
            .map_err(|e| (false, format!("Failed to parse Dexcom readings JSON: {e}")))?;

        let mut readings = Vec::with_capacity(raw.len());
        for item in raw {
            let ts = parse_dexcom_timestamp(&item.wt).map_err(|e| (false, e))?;
            readings.push(GlucoseReading {
                value: item.value,
                trend: item.trend,
                timestamp: ts,
            });
        }
        readings.sort_by_key(|r| r.timestamp);
        Ok(readings)
    }

    pub async fn fetch_readings(
        &self,
        region: &str,
        username: &str,
        password: &str,
        minutes: u32,
        max_count: u32,
    ) -> Result<Vec<GlucoseReading>, String> {
        let cached = self
            .session
            .lock()
            .ok()
            .and_then(|guard| {
                guard.as_ref().and_then(|c| {
                    if c.region.eq_ignore_ascii_case(region) && c.username == username.trim() {
                        Some(c.session_id.clone())
                    } else {
                        None
                    }
                })
            });

        let session_id = match cached {
            Some(id) => id,
            None => {
                let id = self.authenticate(region, username, password).await?;
                if let Ok(mut guard) = self.session.lock() {
                    *guard = Some(CachedSession {
                        region: region.to_string(),
                        username: username.trim().to_string(),
                        session_id: id.clone(),
                    });
                }
                id
            }
        };

        match self
            .read_values_with_session(region, &session_id, minutes, max_count)
            .await
        {
            Ok(readings) => Ok(readings),
            Err((true, _)) => {
                self.clear_session();
                let fresh_id = self.authenticate(region, username, password).await?;
                if let Ok(mut guard) = self.session.lock() {
                    *guard = Some(CachedSession {
                        region: region.to_string(),
                        username: username.trim().to_string(),
                        session_id: fresh_id.clone(),
                    });
                }
                self.read_values_with_session(region, &fresh_id, minutes, max_count)
                    .await
                    .map_err(|(_, msg)| msg)
            }
            Err((false, msg)) => Err(msg),
        }
    }

    pub async fn test_connection(
        &self,
        region: &str,
        username: &str,
        password: &str,
    ) -> Result<String, String> {
        let session_id = self.authenticate(region, username, password).await?;
        let readings = self
            .read_values_with_session(region, &session_id, 1440, 1)
            .await
            .map_err(|(_, msg)| msg)?;

        if let Ok(mut guard) = self.session.lock() {
            *guard = Some(CachedSession {
                region: region.to_string(),
                username: username.trim().to_string(),
                session_id,
            });
        }

        if let Some(latest) = readings.last() {
            Ok(format!(
                "Connected — latest reading: {} mg/dL",
                latest.value
            ))
        } else {
            Ok("Authenticated, but no readings found in the last 24h. Make sure Dexcom Share is enabled with at least 1 follower.".to_string())
        }
    }
}

pub fn parse_dexcom_timestamp(wt: &str) -> Result<i64, String> {
    let start = wt
        .find("Date(")
        .ok_or_else(|| format!("Invalid Dexcom timestamp format: {wt}"))?
        + 5;
    let rest = &wt[start..];
    let end = rest
        .find(')')
        .ok_or_else(|| format!("Unterminated Dexcom timestamp: {wt}"))?;
    let inner = &rest[..end];
    let millis_str = match inner.char_indices().skip(1).find(|(_, c)| *c == '+' || *c == '-') {
        Some((idx, _)) => &inner[..idx],
        None => inner,
    };
    millis_str
        .trim()
        .parse::<i64>()
        .map_err(|e| format!("Failed to parse Dexcom timestamp '{wt}': {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_plain_dexcom_timestamp() {
        assert_eq!(
            parse_dexcom_timestamp("Date(1700000000000)").unwrap(),
            1_700_000_000_000
        );
    }

    #[test]
    fn parses_slash_wrapped_and_offset_timestamps() {
        assert_eq!(
            parse_dexcom_timestamp("/Date(1700000000000)/").unwrap(),
            1_700_000_000_000
        );
        assert_eq!(
            parse_dexcom_timestamp("Date(1700000000000+0000)").unwrap(),
            1_700_000_000_000
        );
        assert_eq!(
            parse_dexcom_timestamp("Date(1700000000000-0500)").unwrap(),
            1_700_000_000_000
        );
    }

    #[test]
    fn deserializes_string_and_integer_trends() {
        let json_str = r#"{"WT":"Date(1700000000000)","Value":120,"Trend":"FortyFiveUp"}"#;
        let r1: RawDexcomReading = serde_json::from_str(json_str).unwrap();
        assert_eq!(r1.trend, TrendDirection::FortyFiveUp);
        assert_eq!(r1.value, 120);

        let json_int = r#"{"WT":"Date(1700000000000)","Value":98,"Trend":4}"#;
        let r2: RawDexcomReading = serde_json::from_str(json_int).unwrap();
        assert_eq!(r2.trend, TrendDirection::Flat);

        let json_double_down = r#"{"WT":"Date(1700000000000)","Value":62,"Trend":7}"#;
        let r3: RawDexcomReading = serde_json::from_str(json_double_down).unwrap();
        assert_eq!(r3.trend, TrendDirection::DoubleDown);
    }
}
