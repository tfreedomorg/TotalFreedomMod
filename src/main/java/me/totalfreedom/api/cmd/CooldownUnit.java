package me.totalfreedom.api.cmd;

public enum CooldownUnit
{
    TICKS
    {
        @Override
        public long toMillis(long value)
        {
            return value * 50L;
        }
    },
    SECONDS
    {
        @Override
        public long toMillis(long value)
        {
            return value * 1000L;
        }
    };

    public abstract long toMillis(long value);
}
