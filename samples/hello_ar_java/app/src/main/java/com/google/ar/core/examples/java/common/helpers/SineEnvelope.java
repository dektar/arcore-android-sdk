/*
 *  Copyright 2016 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.google.ar.core.examples.java.common.helpers;

import com.jsyn.ports.UnitInputPort;
import com.jsyn.ports.UnitOutputPort;
import com.jsyn.unitgen.Circuit;
import com.jsyn.unitgen.EnvelopeDAHDSR;
import com.jsyn.unitgen.LinearRamp;
import com.jsyn.unitgen.PassThrough;
import com.jsyn.unitgen.SineOscillator;
import com.jsyn.unitgen.UnitVoice;
import com.softsynth.shared.time.TimeStamp;

// Based on
// https://github.com/googlearchive/science-journal/blob/1be4c647b0f1e25259bf6b098cbf6416e9be9915/OpenScienceJournal/whistlepunk_library/src/main/java/com/google/android/apps/forscience/whistlepunk/audiogen/voices/SineEnvelope.java
// But with an additional LinearRamp to allow for smooth changes in amplitude.
public class SineEnvelope extends Circuit implements UnitVoice {
    // Declare units and ports.
    PassThrough frequencyPassThrough;
    public UnitInputPort frequency;
    PassThrough amplitudePassThrough;
    public UnitInputPort amplitude;
    PassThrough outputPassThrough;
    public UnitOutputPort output;
    SineOscillator sineOsc;
    EnvelopeDAHDSR dAHDSR;
    LinearRamp lag;

    // Declare inner classes for any child circuits.

    public SineEnvelope() {
        // Create unit generators.
        add(frequencyPassThrough = new PassThrough());
        addPort(frequency = frequencyPassThrough.input, "frequency");
        add(amplitudePassThrough = new PassThrough());
        addPort(amplitude = amplitudePassThrough.input, "amplitude");
        add(outputPassThrough = new PassThrough());
        addPort(output = outputPassThrough.output, "output");
        add(sineOsc = new SineOscillator());
        add(dAHDSR = new EnvelopeDAHDSR());
        add(lag = new LinearRamp());
        // Connect units and ports.
        frequencyPassThrough.output.connect(sineOsc.frequency);
        amplitudePassThrough.output.connect(sineOsc.amplitude);
        sineOsc.output.connect(dAHDSR.amplitude);
        dAHDSR.output.connect(outputPassThrough.input);
        // Setup
        frequency.setup(40.0, 698.4584691287101, 8000.0);
        amplitude.setup(0.0, 0.999969482421875, 1.0);
        dAHDSR.input.set(0.0);
        lag.output.connect(amplitude);
        lag.input.setup( 0.0, 0.5, 1.0 );
        lag.time.set(0.2);

        // Sum of these times should not exceed MIN_TIME_VALUE_CHANGE_MS
        dAHDSR.delay.set(0.01);
        dAHDSR.attack.set(0.01);
        dAHDSR.hold.set(0.04);
        dAHDSR.decay.set(0.01);
        dAHDSR.sustain.set(0.045);
        dAHDSR.release.set(0.01);
    }

    public EnvelopeDAHDSR getDAHDSR() {
        return dAHDSR;
    }

    public void noteOn(double frequency, double amplitude, TimeStamp timeStamp) {
        this.frequency.set(frequency, timeStamp);
        this.lag.input.set(amplitude, timeStamp);
        dAHDSR.input.on(timeStamp);
    }

    public void noteOff(TimeStamp timeStamp) {
        dAHDSR.input.off(timeStamp);
    }

    public UnitOutputPort getOutput() {
        return output;
    }
}