-- One database per service (CLAUDE.md). POSTGRES_DB (catalog) is created by the image itself; this
-- script adds the booking database. It only runs when the data volume is created for the first
-- time; on an existing volume run it by hand (see services/booking-service/README.md).
CREATE DATABASE booking;
