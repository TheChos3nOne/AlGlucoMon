import { TrendDirection } from "../types";

interface TrendArrowProps {
  trend: TrendDirection;
  color: string;
  size?: number;
}

function getRotationDeg(trend: TrendDirection): number | null {
  switch (trend) {
    case "SingleUp":
    case "DoubleUp":
      return -90;
    case "FortyFiveUp":
      return -45;
    case "Flat":
      return 0;
    case "FortyFiveDown":
      return 45;
    case "SingleDown":
    case "DoubleDown":
      return 90;
    default:
      return null;
  }
}

export function TrendArrow({ trend, color, size = 28 }: TrendArrowProps) {
  const rotation = getRotationDeg(trend);
  const isDouble = trend === "DoubleUp" || trend === "DoubleDown";

  if (rotation === null) {
    return (
      <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        className="trend-arrow"
        aria-label={`Trend: ${trend}`}
      >
        <circle cx="8" cy="12" r="1.8" fill={color} opacity="0.7" />
        <circle cx="16" cy="12" r="1.8" fill={color} opacity="0.7" />
      </svg>
    );
  }

  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      className="trend-arrow"
      style={{
        transform: `rotate(${rotation}deg)`,
        filter: `drop-shadow(0 0 6px ${color}55)`,
      }}
      aria-label={`Trend: ${trend}`}
    >
      {isDouble ? (
        <>
          <path
            d="M4.5 7.5H17.5M17.5 7.5L13.5 3.8M17.5 7.5L13.5 11.2"
            stroke={color}
            strokeWidth="2.4"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
          <path
            d="M4.5 16.5H17.5M17.5 16.5L13.5 12.8M17.5 16.5L13.5 20.2"
            stroke={color}
            strokeWidth="2.4"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </>
      ) : (
        <path
          d="M4.5 12H19M19 12L13.2 6.2M19 12L13.2 17.8"
          stroke={color}
          strokeWidth="2.7"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      )}
    </svg>
  );
}
