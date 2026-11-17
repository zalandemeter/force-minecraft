# The client game tests' machine: Java 25, a virtual display, software OpenGL/Vulkan (Mesa), python3 and tmux for the
# demo Force, Noto Color Emoji (the emoji font a Linux terminal uses). No GPU needed.
FROM eclipse-temurin:25-jdk
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      xvfb xauth libgl1 libgl1-mesa-dri libglx-mesa0 libegl1 mesa-vulkan-drivers libvulkan1 \
      libx11-6 libxext6 libxrandr2 libxrender1 libxi6 libxcursor1 libxinerama1 libxxf86vm1 libxtst6 \
      python3 tmux bash procps fonts-noto-color-emoji \
    && rm -rf /var/lib/apt/lists/*
