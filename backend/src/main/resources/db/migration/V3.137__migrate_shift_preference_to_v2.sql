-- shift_requests の UNAVAILABLE を 5段階希望の STRONG_REST に移行
UPDATE shift_requests SET preference = 'STRONG_REST' WHERE preference = 'UNAVAILABLE';

-- member_availability_defaults の UNAVAILABLE を STRONG_REST に移行
UPDATE member_availability_defaults SET preference = 'STRONG_REST' WHERE preference = 'UNAVAILABLE';
