-- Counter operating schedule (R-5.5): weekly time windows per counter. Several windows on one day
-- express breaks (e.g. 08:00-12:00 and 13:00-17:00). A counter without rows has no schedule and is
-- treated as available whenever the site service is open. Site-local, like counter eligibility.
CREATE TABLE counter_hours (
    counter_id   uuid    NOT NULL REFERENCES service_counter (id) ON DELETE CASCADE,
    day_of_week  int     NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),   -- ISO: 1 = Monday
    open_time    time    NOT NULL,
    close_time   time    NOT NULL,
    CHECK (close_time > open_time),
    PRIMARY KEY (counter_id, day_of_week, open_time)
);
