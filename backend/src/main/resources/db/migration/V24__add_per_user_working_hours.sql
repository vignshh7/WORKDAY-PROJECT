-- Lets a user override the org-wide scheduling_config working hours with their own preferred
-- window, in their own timezone (users.timezone). NULL on either column means "use the org
-- default" - most users never set this, so it stays optional rather than forcing a value.
-- Only consulted for a Google-connected user's computed availability (WorkingHoursService's
-- org default remains the sole source for a manual-Availability user, whose own declared rows
-- already are their exact preferred times).
ALTER TABLE users ADD COLUMN working_start TIME;
ALTER TABLE users ADD COLUMN working_end TIME;
