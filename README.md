# BLE Android Plugin

This repository contains an Android plugin that gives the possibility to use BLE in [Godot applications](https://godotengine.org).
The module to build and use is the one contained in the directory `bleframework/`.


## Setup

### Building the plugin in Android Studio

After downloading this repository, open the relative folder with Android Studio, and then build the module **bleframework**. Once the operation is done, you will find the **Android archive library** (*aar* archive file) in *bleframework/build/outputs/aar/*.

### Android Environment in Godot

In order to build an Android application, you need to set up the Godot environment as explained [here](https://docs.godotengine.org/it/stable/getting_started/workflow/export/android_custom_build.html).

### Loading the plugin

Move the plugin configuration file (`bleframework/bleframework.gdap`) and the local binary (`bleframework/build/outputs/aar/bleframework-debug.aar`) that you generated to the Godot project's `res://android/plugins` directory (if they do not exist, create them).
The Godot editor will automatically parse the **.gdap** file and show our plugin **"BLE Plugin"** in the Android export presets window under the **Plugins** section.
Once you enable it, you can use it in your code as explained in the next section.

## How to use the plugin

The plugin makes available the main functionalities for an Android device (central) to connect and communicate with a peripheral.
The main functionalities are:
- start scanning / stop scanning
- set filters for the scanning
- connect/disconnect to/from a device
- enable/disable characteristic's notifications (and receive them)
- read/write a characteristic

To use the plugin in Godot, in your script you have to retrieve the correspondent singleton, as shown below.
``` gdnative
if Engine.has_singleton("BLEPlugin"):
    var ble = Engine.get_singleton("BLEPlugin") 
    ble.initialize()
```

The variable `ble` is the object that contains all the methods of the Java class, through which you can access the BLE operations.

Since the Android BLE library uses many callbacks in Java, in those cases they carry meaningful information a signal is emitted so that it can also be seen in Godot. To receive these signals and thus perform some actions, the signal has to be connected to a function in the Godot script as shown below.
```gdnative
ble.connect("device_found", self, "_on_dev_found")
```
In this way, when the signal "device_found" is emitted, the function "_on_dev_found" is instantly executed.

## Authors
- [Francesca Romana Mattei](https://github.com/francescaromana) 
- [Massimiliano Schembri](https://github.com/schembrimax) 

## Licence

The provided software is free and open source,and released under MIT licence.

> Copyright 2021,  [ImTwin](www.im-twin.eu) and [PlusMe](www.plusme-h2020.eu) European projects
>
>Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
>
>The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
>
>THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Funding
<img align="left" src="/images/EU_logo.png" width="100"> This software is part of two projects that have received funding from the European Union’s Horizon 2020 research and innovation programme under grant agreement No 952095 and No 945887


<p>&nbsp;</p>
<img align="left" src="/images/imtwin-logo_2020.png" width="100"> *from Intrinsic Motivations to Transitional Wearable INtelligent companions for autism spectrum disorder*

www.im-twin.eu

<p>&nbsp;</p>
<p>&nbsp;</p>
<img align="left" src="/images/plusme_logo.png" width="85"> *PlusMe: Transitional Wearable Companions for the therapy of children with Autism Spectrum Disorders*

www.plusme-h2020.eu 


