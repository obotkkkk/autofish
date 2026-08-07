package com.autofish;

public class PIDController {
    private final double kp;
    private final double ki;
    private final double kd;
    private double lastError = 0;
    private double integral = 0;

    public PIDController(double kp, double ki, double kd) {
        this.kp = kp;
        this.ki = ki;
        this.kd = kd;
    }

    public double calculate(double target, double current) {
        double error = target - current;
        integral += error;
        double derivative = error - lastError;
        lastError = error;
        return (kp * error) + (ki * integral) + (kd * derivative);
    }

    public void reset() {
        lastError = 0;
        integral = 0;
    }
}
