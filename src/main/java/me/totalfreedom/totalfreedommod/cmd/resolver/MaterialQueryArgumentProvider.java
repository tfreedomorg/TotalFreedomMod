package me.totalfreedom.totalfreedommod.cmd.resolver;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.bukkit.Material;
import org.bukkit.Registry;

public class MaterialQueryArgumentProvider implements AbstractParameterizedArgumentResolver<List<Material>>
{
    @Override
    public String name()
    {
        return "MaterialQuery";
    }

    /**
     * The argument is a regex matched against material keys, so there is no exact candidate set.
     * Offering the keys themselves still helps: a bare key is a valid pattern that matches itself.
     */
    @Override
    public List<String> suggestions()
    {
        return Registry.MATERIAL.stream()
                                .map(material -> material.key().value())
                                .sorted()
                                .toList();
    }

    @Override
    public List<Material> resolve(String arg, Map<String, Object> parameters)
    {
        final String mode = ((String) parameters.getOrDefault("mode", "normal")).toLowerCase();
        final int minLength = (int) parameters.getOrDefault("minLength", 0);
        final long limit = (long) parameters.getOrDefault("limit", Long.MAX_VALUE);

        if (minLength > arg.length())
        {
            throw new IllegalArgumentException("Query was too short, must be at least " + limit + " characters long");
        }

        final Pattern pattern;

        try
        {
            pattern = Pattern.compile(arg.toLowerCase());
        }
        catch (PatternSyntaxException ex)
        {
            throw new ArgumentResolutionException(ex);
        }

        return Registry.MATERIAL.stream()
                .filter(material -> pattern.matcher(material.key().asString()).find())
                .filter(material -> switch (mode)
                {
                    case "blocks" -> material.isBlock();
                    case "items" -> material.isItem();
                    default -> true;
                })
                .limit(limit)
                .toList();
    }
}
