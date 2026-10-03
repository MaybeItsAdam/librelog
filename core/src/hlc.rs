//! Hybrid logical clock. Every synced field change is stamped with one, and
//! the higher stamp wins a merge (SPEC.md §12.5).
//!
//! A stamp renders as `MMMMMMMMMMMMM-CCCC-<device>`: 13-digit wall-clock
//! milliseconds, 4 hex digits of counter, and the device id as a tiebreak.
//! Fixed widths make plain string comparison agree with clock order, so
//! stamps can be compared in SQL as text.

use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct Stamp {
    pub millis: u64,
    pub counter: u16,
}

#[derive(Debug, Clone)]
pub struct Clock {
    last: Stamp,
    device: String,
}

impl Clock {
    pub fn new(device: String, last: Option<&str>) -> Self {
        let last = last
            .and_then(parse)
            .map(|(s, _)| s)
            .unwrap_or(Stamp { millis: 0, counter: 0 });
        Clock { last, device }
    }

    /// A new stamp for a local change, strictly after every stamp seen.
    pub fn tick(&mut self) -> String {
        self.tick_at(now_millis())
    }

    pub(crate) fn tick_at(&mut self, wall: u64) -> String {
        self.last = if wall > self.last.millis {
            Stamp { millis: wall, counter: 0 }
        } else {
            self.bump(self.last)
        };
        render(self.last, &self.device)
    }

    /// Folds in a stamp from another device so later local stamps sort after it.
    pub fn observe(&mut self, remote: &str) {
        self.observe_at(remote, now_millis());
    }

    pub(crate) fn observe_at(&mut self, remote: &str, wall: u64) {
        let Some((r, _)) = parse(remote) else { return };
        let top = self.last.max(r);
        self.last = if wall > top.millis {
            Stamp { millis: wall, counter: 0 }
        } else {
            self.bump(top)
        };
    }

    fn bump(&self, s: Stamp) -> Stamp {
        match s.counter.checked_add(1) {
            Some(c) => Stamp { millis: s.millis, counter: c },
            // 65,536 changes inside one millisecond: borrow the next one.
            None => Stamp { millis: s.millis + 1, counter: 0 },
        }
    }
}

pub fn render(s: Stamp, device: &str) -> String {
    format!("{:013}-{:04x}-{}", s.millis, s.counter, device)
}

pub fn parse(stamp: &str) -> Option<(Stamp, &str)> {
    let mut parts = stamp.splitn(3, '-');
    let millis = parts.next()?.parse().ok()?;
    let counter = u16::from_str_radix(parts.next()?, 16).ok()?;
    let device = parts.next()?;
    Some((Stamp { millis, counter }, device))
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ticks_are_strictly_increasing_even_when_the_wall_clock_stalls() {
        let mut c = Clock::new("a".into(), None);
        let a = c.tick_at(1000);
        let b = c.tick_at(1000);
        let d = c.tick_at(999);
        assert!(a < b && b < d, "{a} {b} {d}");
    }

    #[test]
    fn observing_a_future_stamp_moves_local_ticks_past_it() {
        let mut c = Clock::new("a".into(), None);
        c.tick_at(1000);
        c.observe_at(&render(Stamp { millis: 5000, counter: 3 }, "b"), 1000);
        let next = c.tick_at(1000);
        assert!(next > render(Stamp { millis: 5000, counter: 3 }, "b"), "{next}");
    }

    #[test]
    fn string_order_matches_clock_order() {
        let a = render(Stamp { millis: 999, counter: 0xffff }, "z");
        let b = render(Stamp { millis: 1000, counter: 0 }, "a");
        assert!(a < b);
    }

    #[test]
    fn parse_round_trips() {
        let s = render(Stamp { millis: 42, counter: 7 }, "dev-1");
        let (p, d) = parse(&s).unwrap();
        assert_eq!((p.millis, p.counter, d), (42, 7, "dev-1"));
    }
}
