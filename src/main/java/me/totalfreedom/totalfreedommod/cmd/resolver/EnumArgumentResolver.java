package me.totalfreedom.totalfreedommod.cmd.resolver;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Function;

import com.google.common.base.Enums;
import com.google.common.base.Optional;

public class EnumArgumentResolver implements AbstractParameterizedArgumentResolver<Enum<?>>
{

    @Override
    public String name()
    {
        return "Enum";
    }

    @Override
    public Enum<?> resolve(String arg, Map<String, Object> parameters)
    {
        final String clazzString = (String) parameters.get("class");
        final EnumCapitalization mode = EnumCapitalization.fromString((String) parameters.getOrDefault("mode", "none"));

        if (clazzString == null)
        {
            throw new IllegalArgumentException("Missing required 'class' parameter. Please contact a developer about this immediately!");
        }

        try
        {
            return fetchEnumObject(clazzString, mode.acceptInput(arg));
        }
        catch (ClassCastException ex)
        {
            throw new IllegalArgumentException("Enum class was found, but it is not what we're looking for", ex);
        }
        catch (ClassNotFoundException ex)
        {
            throw new IllegalArgumentException("Unable to find enum class " + clazzString, ex);
        }
    }

    public <T extends Enum<T>> Enum<T> fetchEnumObject(String clazz, String arg)
            throws ClassNotFoundException
    {
        final Class<T> enumClazz = (Class<T>) Class.forName(clazz);

        // Let's try a generic "fromString" method first. Just in case...
        try
        {
            final Method method = enumClazz.getDeclaredMethod("fromString", String.class);
            method.setAccessible(true); // the enum is often a private nested type; without this the invoke silently falls through to valueOf
            return (Enum<T>) method.invoke(null, arg);
        }
        // Well, we got an IAE, so at least the method exists but just kind of... sucks. We'll rethrow it.
        catch (IllegalArgumentException ex)
        {
            throw new ArgumentResolutionException(ex);
        }
        // Well, it doesn't exist. Oh well, fall back onto just using valueOf.
        catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException _)
        {
        }

        final Optional<T> object = Enums.getIfPresent(enumClazz, arg);

        if (!object.isPresent())
        {
            throw new ArgumentResolutionException("Invalid mode: " + arg);
        }

        return object.get();
    }

    public enum EnumCapitalization
    {
        LOWERCASE(String::toLowerCase),
        UPPERCASE(String::toUpperCase),
        NONE;

        private final Function<String, String> transformer;

        EnumCapitalization()
        {
            this.transformer = null;
        }

        EnumCapitalization(Function<String, String> action)
        {
            this.transformer = action;
        }

        public String acceptInput(String input)
        {
            if (transformer == null)
            {
                return input;
            }

            return transformer.apply(input);
        }

        public static EnumCapitalization fromString(String value)
        {
            try
            {
                return valueOf(value.toUpperCase());
            }
            catch (IllegalArgumentException _)
            {
                throw new IllegalArgumentException("Invalid capitalization mode: " + value);
            }
        }
    }
}
