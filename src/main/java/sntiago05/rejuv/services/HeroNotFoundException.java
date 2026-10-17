package sntiago05.rejuv.services;

public class HeroNotFoundException extends RuntimeException {

    public HeroNotFoundException(String input) {
        super("Hero not found: " + input);
    }
}
