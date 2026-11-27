# Manufacturer profiles

Signals marked **MFR** in the app (brake pedal and pressure, steering angle, wheel speeds,
TPMS pressure and temperature, turn signals, seatbelts, car acceleration) are not part of
standard OBD-II. Each car maker exposes them through its own PIDs, usually mode 22 requests
sent to a specific module (ABS/ESC, body control, TPMS). A profile tells the app which
requests to send and how to decode the reply.

Profiles are plain JSON. Put new ones here (built in) or import one from the phone in
**Settings › Manufacturer profile › Import profile**. No rebuild needed.

```json
{
  "id": "my-car",
  "name": "2021 Example Sedan",
  "notes": "Where the PIDs came from",
  "wmi": ["1HG"],                       // first 3 VIN characters, for Auto matching
  "brakes": { "frontPistonAreaCm2": 25.5, "rearPistonAreaCm2": 11.3 },
  "initCommands": [],                   // optional extra AT commands
  "signals": [
    { "signal": "brakePressure", "header": "7B0", "request": "22 2B0D",
      "formula": "(A*256+B)/100", "unit": "bar", "intervalMs": 150 }
  ]
}
```

* `header` – the module's CAN request ID (11-bit like `7B0`, or 29-bit like `18DA28F1`).
  The app sets the receive filter to header + 8 for 11-bit IDs.
* `request` – the hex request; the expected reply prefix is derived (mode + 0x40, then the PID).
  Override with `responsePrefix` if the module answers differently.
* `formula` – `A`, `B`, `C`… are the reply bytes after the prefix. Operators: `+ - * / % & | ^ << >>`,
  functions `signed(x,bits)`, `bit(x,n)`, `min`, `max`, `abs`. Boolean signals are true when non-zero.
* `unit` – what the formula returns. Converted to the internal unit automatically:
  pressure `bar`/`kPa`/`psi`/`mbar`, speed `km/h`/`mph`, temperature `C`/`F`, accel `g`/`m/s2`, distance `km`/`mi`.

Signal ids: `brakePedal`, `brakePressure`, `steeringAngle`, `wheelSpeedFL/FR/RL/RR`,
`tirePressureFL/FR/RL/RR` (gauge pressure), `tireTempFL/FR/RL/RR`, `turnLeft`, `turnRight`,
`seatbeltDriver`, `seatbeltPassenger`, `accelLong`, `accelLat`, `odometer`.

Tips: PID lists for many cars are published by the Torque / Car Scanner communities and in
forum threads for each model. Test one signal at a time; anything that fails three times is
shown as N/A and retried every 30 seconds. Some cars (many 2018+ FCA/Stellantis, some VW group)
put a security gateway in front of the OBD port that blocks these requests.
