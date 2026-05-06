#!/bin/bash

# Wasted 2026 - Build & Deploy Script
# Supports multi-device management with interactive menu

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
WHITE='\033[1;37m'
NC='\033[0m' # No Color

# Paths
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_DEBUG="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
APK_RELEASE="$PROJECT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"

# ============================================================================
# Helper Functions
# ============================================================================

print_header() {
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${CYAN}  Wasted 2026 - Build & Multi-Device Deployment${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

print_command() {
    echo -e "${MAGENTA}$ $1${NC}"
}

# Get connected devices
get_devices() {
    adb devices | grep -E "^\S+\s+device$" | awk '{print $1}' | grep -v "^List" | grep -v "^$"
}

# Get device model name
get_device_model() {
    local device=$1
    adb -s "$device" shell getprop ro.model.name 2>/dev/null || echo "Unknown"
}

# Get Android version
get_android_version() {
    local device=$1
    adb -s "$device" shell getprop ro.build.version.release 2>/dev/null || echo "Unknown"
}

# Display available devices
show_devices() {
    echo ""
    echo -e "${CYAN}Connected Devices:${NC}"
    
    local devices=($(get_devices))
    
    if [ ${#devices[@]} -eq 0 ]; then
        print_error "No devices connected!"
        return 1
    fi
    
    echo -e "${WHITE}┌────────────────────────────────────────────────────────────────┐${NC}"
    
    for i in "${!devices[@]}"; do
        local device="${devices[$i]}"
        local model=$(get_device_model "$device")
        local android=$(get_android_version "$device")
        local idx=$((i + 1))
        
        printf "${WHITE}│${NC} ${GREEN}[$idx]${NC} ${CYAN}%-20s${NC} Android: ${YELLOW}%-6s${NC} ${BLUE}%s${NC}\n" \
            "$device" "$android" "$model"
    done
    
    echo -e "${WHITE}└────────────────────────────────────────────────────────────────┘${NC}"
    echo ""
    
    return 0
}

# Show main menu
show_menu() {
    echo ""
    echo -e "${CYAN}Build Options:${NC}"
    echo -e "${WHITE}  [1]${NC} ${BLUE}Build Debug APK${NC}"
    echo -e "${WHITE}  [2]${NC} ${BLUE}Build Release APK${NC}"
    echo -e "${WHITE}  [3]${NC} ${BLUE}Install to All Devices${NC}"
    echo -e "${WHITE}  [4]${NC} ${BLUE}Install to Specific Device${NC}"
    echo -e "${WHITE}  [5]${NC} ${BLUE}Build & Install to All${NC}"
    echo -e "${WHITE}  [6]${NC} ${BLUE}Show Logcat (Live)${NC}"
    echo -e "${WHITE}  [7]${NC} ${BLUE}Launch App${NC}"
    echo -e "${WHITE}  [0]${NC} ${RED}Exit${NC}"
    echo ""
}

# ============================================================================
# Build Functions
# ============================================================================

build_debug() {
    echo ""
    print_info "Building Debug APK..."
    print_command "./gradlew assembleDebug"
    echo ""
    
    cd "$PROJECT_DIR"
    if ./gradlew assembleDebug; then
        print_success "Debug APK built successfully"
        print_info "Location: $APK_DEBUG"
        return 0
    else
        print_error "Build failed"
        return 1
    fi
}

build_release() {
    echo ""
    print_info "Building Release APK..."
    print_command "./gradlew assembleRelease"
    echo ""
    
    cd "$PROJECT_DIR"
    if ./gradlew assembleRelease; then
        print_success "Release APK built successfully"
        print_info "Location: $APK_RELEASE"
        return 0
    else
        print_error "Build failed"
        return 1
    fi
}

# ============================================================================
# Installation Functions
# ============================================================================

install_apk() {
    local device=$1
    local apk=$2
    
    if [ ! -f "$apk" ]; then
        print_error "APK not found: $apk"
        return 1
    fi
    
    local model=$(get_device_model "$device")
    local android=$(get_android_version "$device")
    
    echo ""
    echo -e "${CYAN}Installing to:${NC} ${GREEN}$device${NC} (${model}, Android ${android})"
    print_command "adb -s $device install -r $apk"
    echo ""
    
    if adb -s "$device" install -r "$apk"; then
        print_success "Installation successful on $device"
        return 0
    else
        print_error "Installation failed on $device"
        return 1
    fi
}

install_all_devices() {
    local apk=$1
    local apk_type=${2:-"Debug"}
    
    if [ ! -f "$apk" ]; then
        print_error "APK not found: $apk"
        return 1
    fi
    
    local devices=($(get_devices))
    
    if [ ${#devices[@]} -eq 0 ]; then
        print_error "No devices connected"
        return 1
    fi
    
    echo ""
    echo -e "${CYAN}Installing ${apk_type} APK to ${#devices[@]} device(s)...${NC}"
    echo -e "${WHITE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    
    local failed=0
    for device in "${devices[@]}"; do
        if ! install_apk "$device" "$apk"; then
            ((failed++))
        fi
    done
    
    echo ""
    echo -e "${WHITE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    
    if [ $failed -eq 0 ]; then
        print_success "All installations successful!"
        return 0
    else
        print_warning "$failed installation(s) failed"
        return 1
    fi
}

install_to_specific() {
    local devices=($(get_devices))
    
    if [ ${#devices[@]} -eq 0 ]; then
        print_error "No devices connected"
        return 1
    fi
    
    echo ""
    echo -e "${CYAN}Select device:${NC}"
    
    for i in "${!devices[@]}"; do
        local device="${devices[$i]}"
        local model=$(get_device_model "$device")
        local idx=$((i + 1))
        echo -e "${WHITE}  [$idx]${NC} $device (${model})"
    done
    
    echo ""
    read -p "Enter device number (or 0 to cancel): " device_choice
    
    if [ "$device_choice" -eq 0 ] 2>/dev/null; then
        print_info "Cancelled"
        return 0
    fi
    
    local device_idx=$((device_choice - 1))
    
    if [ $device_idx -lt 0 ] || [ $device_idx -ge ${#devices[@]} ]; then
        print_error "Invalid selection"
        return 1
    fi
    
    local selected_device="${devices[$device_idx]}"
    
    echo ""
    echo -e "${CYAN}Select APK type:${NC}"
    echo -e "${WHITE}  [1]${NC} Debug"
    echo -e "${WHITE}  [2]${NC} Release"
    read -p "Choose (1-2): " apk_choice
    
    local apk
    if [ "$apk_choice" = "1" ]; then
        apk="$APK_DEBUG"
    elif [ "$apk_choice" = "2" ]; then
        apk="$APK_RELEASE"
    else
        print_error "Invalid selection"
        return 1
    fi
    
    install_apk "$selected_device" "$apk"
}

# ============================================================================
# Launch Functions
# ============================================================================

launch_app() {
    local devices=($(get_devices))
    
    if [ ${#devices[@]} -eq 0 ]; then
        print_error "No devices connected"
        return 1
    fi
    
    local device="${devices[0]}"
    
    if [ ${#devices[@]} -gt 1 ]; then
        echo ""
        echo -e "${CYAN}Multiple devices found, launching on first device:${NC}"
        echo -e "${YELLOW}$device${NC}"
    fi
    
    echo ""
    print_info "Launching app on $device..."
    print_command "adb -s $device shell am start -n me.lucky.wasted/.MainActivity"
    echo ""
    
    adb -s "$device" shell am start -n me.lucky.wasted/.MainActivity
    print_success "App launched"
}

# ============================================================================
# Logcat Functions
# ============================================================================

show_logcat() {
    local devices=($(get_devices))
    
    if [ ${#devices[@]} -eq 0 ]; then
        print_error "No devices connected"
        return 1
    fi
    
    echo ""
    
    if [ ${#devices[@]} -eq 1 ]; then
        local device="${devices[0]}"
        echo -e "${CYAN}Streaming logcat from: ${GREEN}$device${NC}"
    else
        echo -e "${CYAN}Multiple devices connected:${NC}"
        for i in "${!devices[@]}"; do
            local device="${devices[$i]}"
            local idx=$((i + 1))
            echo -e "${WHITE}  [$idx]${NC} $device"
        done
        
        echo ""
        read -p "Enter device number: " logcat_choice
        local logcat_idx=$((logcat_choice - 1))
        
        if [ $logcat_idx -lt 0 ] || [ $logcat_idx -ge ${#devices[@]} ]; then
            print_error "Invalid selection"
            return 1
        fi
        
        local device="${devices[$logcat_idx]}"
    fi
    
    echo ""
    echo -e "${YELLOW}Press Ctrl+C to stop and return to menu${NC}"
    echo -e "${WHITE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""
    
    # Clear logcat and start fresh
    adb -s "$device" logcat --clear 2>/dev/null
    
    # Set up trap to handle Ctrl+C gracefully
    trap 'echo ""; return 0' SIGINT
    
    # Show logcat filtered to our app package with errors highlighted
    adb -s "$device" logcat --format=threadtime "me.lucky.wasted" \
        | while IFS= read -r line; do
            # Highlight errors in red
            if [[ "$line" == *"ERROR"* ]] || [[ "$line" == *"FATAL"* ]] || [[ "$line" == *"Exception"* ]]; then
                echo -e "${RED}$line${NC}"
            # Highlight P2P messages in cyan
            elif [[ "$line" == *"P2PNetwork"* ]] || [[ "$line" == *"DeviceDiscovery"* ]] || [[ "$line" == *"MessageServer"* ]] || [[ "$line" == *"SettingsSync"* ]]; then
                echo -e "${CYAN}$line${NC}"
            # Highlight security in magenta
            elif [[ "$line" == *"SecurityManager"* ]] || [[ "$line" == *"Pairing"* ]]; then
                echo -e "${MAGENTA}$line${NC}"
            # Normal info
            else
                echo "$line"
            fi
        done
    
    trap - SIGINT
}

# ============================================================================
# Build & Install Combined
# ============================================================================

build_and_install_all() {
    echo ""
    echo -e "${CYAN}Building and installing to all devices...${NC}"
    echo ""
    
    if ! build_debug; then
        return 1
    fi
    
    if ! install_all_devices "$APK_DEBUG" "Debug"; then
        return 1
    fi
    
    echo ""
    read -p "Launch app on first device? (y/n): " launch_choice
    if [[ "$launch_choice" == "y" || "$launch_choice" == "Y" ]]; then
        launch_app
    fi
}

# ============================================================================
# Main Loop
# ============================================================================

main() {
    while true; do
        print_header
        
        if ! show_devices; then
            print_warning "Connect devices and try again"
            echo ""
            read -p "Press Enter to continue..."
            continue
        fi
        
        show_menu
        read -p "Choose option: " choice
        
        case $choice in
            1)
                build_debug
                read -p "Press Enter to continue..."
                ;;
            2)
                build_release
                read -p "Press Enter to continue..."
                ;;
            3)
                install_all_devices "$APK_DEBUG" "Debug"
                read -p "Press Enter to continue..."
                ;;
            4)
                install_to_specific
                read -p "Press Enter to continue..."
                ;;
            5)
                build_and_install_all
                read -p "Press Enter to continue..."
                ;;
            6)
                show_logcat
                ;;
            7)
                launch_app
                read -p "Press Enter to continue..."
                ;;
            0)
                print_info "Exiting..."
                exit 0
                ;;
            *)
                print_error "Invalid option"
                read -p "Press Enter to continue..."
                ;;
        esac
        
        clear
    done
}

# ============================================================================
# Entry Point
# ============================================================================

# Check if adb is available
if ! command -v adb &> /dev/null; then
    print_error "adb not found. Please install Android SDK."
    exit 1
fi

# Check if we're in the right directory
if [ ! -f "$PROJECT_DIR/build.gradle" ]; then
    print_error "build.gradle not found. Run this script from project root."
    exit 1
fi

# Handle command-line arguments
if [ $# -gt 0 ]; then
    case "$1" in
        build)
            build_debug
            ;;
        release)
            build_release
            ;;
        install-all)
            install_all_devices "$APK_DEBUG" "Debug"
            ;;
        install-release)
            install_all_devices "$APK_RELEASE" "Release"
            ;;
        launch)
            launch_app
            ;;
        logcat)
            show_logcat
            ;;
        *)
            echo "Usage: $0 [build|release|install-all|install-release|launch|logcat]"
            echo "Or run without arguments for interactive menu"
            exit 1
            ;;
    esac
else
    clear
    main
fi
