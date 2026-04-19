package com.example.a5466group11app.protocol;

public class CommandBuilder {

    private CommandBuilder() {
    }

    public static String verify() {
        return "W#4#FREENOVE#\n";
    }

    public static String standToggle() {
        return "A#0#\n";
    }

    public static String standUp() {
        return "A#1#\n";
    }

    public static String lieDown() {
        return "A#2#\n";
    }

    public static String move(int p1, int p2, int p3, int p4) {
        return "F#" + p1 + "#" + p2 + "#" + p3 + "#" + p4 + "#\n";
    }

    public static String twist(int p1, int p2, int p3) {
        return "E#" + p1 + "#" + p2 + "#" + p3 + "#\n";
    }

    public static String dance(int danceId) {
        return "O#" + danceId + "#\n";
    }

    public static String stopMove() {
        return move(0, 0, 0, 0);
    }

    public static String moveForward() {
        return move(20, 0, 0, 5);
    }

    public static String moveBackward() {
        return move(-20, 0, 0, 5);
    }

    public static String turnLeft() {
        return move(0, 0, -20, 5);
    }

    public static String turnRight() {
        return move(0, 0, 20, 5);
    }

    public static String twistLeft() {
        return twist(-15, 0, 0);
    }

    public static String twistRight() {
        return twist(15, 0, 0);
    }
}